package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowScope;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import org.junit.jupiter.api.Test;

/**
 * Pins the invariant that {@link OutboxWorker#shutdown()} cooperatively cancels any in-flight
 * dispatch the worker's daemon thread is currently performing.
 *
 * <p>Pre-fix, {@code rebuildContextFor} built a fresh {@code CancellationToken.create()} per record
 * — orphaned, not linked to the worker's lifecycle. {@code shutdown()} interrupted the daemon
 * thread and joined with a 5s grace, but a listener that polled {@code
 * ctx.throwIfCancelledOrExpired()} (rather than blocking on interruptible code) would never observe
 * cancellation, leaving the daemon stuck inside the listener and {@code thread.join(5_000)} timing
 * out without actually stopping the work.
 *
 * <p>The current implementation owns one worker-lifetime {@link
 * net.nexus_flow.core.runtime.CancellationToken} and feeds it into every {@link ExecutionContext}
 * the worker builds. {@code shutdown()} cancels that token <em>before</em> {@link
 * Thread#interrupt()}, so handlers polling the context observe cooperative cancellation regardless
 * of whether they happen to be in an interruptible block.
 *
 * <p>Cancellation remains cooperative: a handler that polls NEITHER the context NOR interruption
 * cannot be force-stopped. {@code shutdown()} returns once {@code thread.join(5_000)} elapses, but
 * the daemon may still be running. This test focuses on the cooperative case (handler polls the
 * context).
 */
class OutboxWorkerCancelsInFlightDispatchOnShutdownTest {

    static final class Beat extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Beat(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Heart extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void beat() {
            recordEvent(new Beat("heart-cancel"));
        }
    }

    /**
     * Listener that polls {@link ExecutionContext#throwIfCancelledOrExpired()} in a short loop while
     * holding the worker's daemon thread inside its {@code handle} method. Captures the throwable so
     * the test can assert which cancellation primitive fired.
     */
    static final class CooperativelyCancellableListener extends AbstractDomainEventListener<Beat> {
        final CountDownLatch             listenerEntered = new CountDownLatch(1);
        final AtomicReference<Throwable> observed        = new AtomicReference<>();

        @Override
        public void handle(Beat event) {
            listenerEntered.countDown();
            ExecutionContext ctx = FlowScope.current().orElseThrow();
            // 10s safety cap — under the fix, cancellation fires within ~20ms of shutdown().
            long deadlineNanos = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            try {
                while (System.nanoTime() < deadlineNanos) {
                    ctx.throwIfCancelledOrExpired();
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        observed.set(ie);
                        // Surface as a runtime so the dispatcher sees the failure.
                        throw new RuntimeException(ie);
                    }
                }
                // Safety exit — no cancellation observed within 10s. The test
                // assertions below will catch this as a regression.
            } catch (FlowCancellationException fce) {
                observed.set(fce);
                throw fce;
            }
        }
    }

    @Test
    void shutdown_cancelsInFlightListenerWithinGraceWindow() throws Exception {
        Clock                 clock   = Clock.fixed(java.time.Instant.parse("2026-05-23T15:00:00Z"), ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec   = new JavaSerializationOutboxPayloadCodec();

        // Append exactly one PENDING row so the worker will pick it up
        // and hand it to the listener on its first claim.
        Heart heart = new Heart();
        heart.beat();
        List<DomainEvent> drained = heart.drainEvents();
        OutboxAppender.appendDrainedEvents(drained, ExecutionContext.root(), storage, clock, codec);

        EventBus                         bus      = EventBus.newInstance();
        CooperativelyCancellableListener listener = new CooperativelyCancellableListener();
        bus.register(listener);

        OutboxConfig config =
                OutboxConfig.builder(storage, codec)
                        .clock(clock)
                        .useOutboxFanOut(true)
                        .workerPollInterval(Duration.ofMillis(10))
                        .workerBatchSize(1)
                        .build();
        OutboxWorker worker = new OutboxWorker(config, bus, ErrorPolicy.failFast());

        try {
            worker.start();

            // Wait for the daemon to enter the listener. If this times out the
            // worker never delivered the row and the test scenario is broken.
            assertTrue(
                       listener.listenerEntered.await(5, TimeUnit.SECONDS),
                       "outbox worker did not deliver to listener within 5s — scenario broken");

            // Now the daemon is blocked inside the listener's poll loop. Trigger
            // shutdown and measure how long it takes to return.
            long t0 = System.nanoTime();
            worker.shutdown();
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            // Cooperative cancellation should fire within ~20ms (the poll period).
            // Bound liberally at 2s to absorb CI noise; the 5s join grace would
            // be a clear regression if hit.
            assertTrue(
                       elapsedMs < 2_000L,
                       "shutdown() took " + elapsedMs + "ms; expected < 2s (5s join grace would be regression)");

            // The listener must have observed cooperative cancellation through its
            // ExecutionContext — proof that the worker's CancellationToken was
            // cancelled BEFORE thread.interrupt() and was the SAME token the
            // listener's context referenced.
            Throwable observed = listener.observed.get();
            assertNotNull(
                          observed,
                          "listener did not observe cancellation or interruption — shutdown is not cooperative");
            assertTrue(
                       observed instanceof FlowCancellationException || observed instanceof InterruptedException,
                       "listener observed unexpected throwable type: " + observed.getClass().getName());

            // Sanity: the worker is no longer running.
            assertFalse(worker.isRunning(), "worker reports running after shutdown()");
        } finally {
            // Idempotent second close in case the assertion above fired before shutdown.
            worker.shutdown();
        }
    }
}
