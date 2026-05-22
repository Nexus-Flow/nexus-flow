package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link OutboxConfig#workerShutdownGrace()} as the controlling knob for the {@link
 * Thread#join(long)} timeout inside {@link OutboxWorker#shutdown()}.
 *
 * <p>Pre-fix the value was hardcoded to {@code 5_000} ms. A handler that ignored both cooperative
 * cancellation and {@code interrupt()} (the "perfect cancellation contract" lower bound) would hold
 * {@code shutdown()} blocked for the full 5 seconds with no caller override.
 *
 * <p>Post-fix the grace is configurable per worker via {@link
 * OutboxConfig.Builder#workerShutdownGrace}. This test installs a handler that ignores
 * cancellation, sets a 300 ms grace, and asserts that {@code shutdown()} returns within the
 * configured window — proving the knob is wired through.
 */
class OutboxWorkerShutdownGraceConfigurableTest {

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
            recordEvent(new Beat("heart-grace"));
        }
    }

    /**
     * Handler that ignores cancellation AND interruption — the worst case for graceful shutdown. The
     * test below relies on {@code workerShutdownGrace} to bound the wait; the handler would otherwise
     * block forever.
     */
    static final class UncooperativeListener extends AbstractDomainEventListener<Beat> {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void handle(Beat event) {
            entered.countDown();
            // Wait WITHOUT InterruptedException propagation — emulates a handler
            // that blocks on non-interruptible code (e.g. an old JDBC driver,
            // a native call) and does not poll the context.
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException _) {
                    // Swallow + reset to simulate badly-written code.
                    Thread.interrupted();
                }
            }
        }
    }

    @Test
    void shutdownGraceConfigurable_boundsTheWaitForUncooperativeHandlers() throws Exception {
        Clock                 clock   = Clock.systemUTC();
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec   = new JavaSerializationOutboxPayloadCodec();

        Heart heart = new Heart();
        heart.beat();
        List<DomainEvent> drained = heart.drainEvents();
        OutboxAppender.appendDrainedEvents(drained, ExecutionContext.root(), storage, clock, codec);

        EventBus              bus      = EventBus.newInstance();
        UncooperativeListener listener = new UncooperativeListener();
        bus.register(listener);

        Duration     grace  = Duration.ofMillis(300);
        OutboxConfig config =
                OutboxConfig.builder(storage, codec)
                        .clock(clock)
                        .useOutboxFanOut(true)
                        .workerPollInterval(Duration.ofMillis(10))
                        .workerBatchSize(1)
                        .workerShutdownGrace(grace)
                        .build();
        OutboxWorker worker = new OutboxWorker(config, bus, ErrorPolicy.failFast());

        try {
            worker.start();

            assertTrue(
                       listener.entered.await(3, TimeUnit.SECONDS),
                       "uncooperative listener never received the event — scenario broken");

            long t0 = System.nanoTime();
            worker.shutdown();
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            // Allow up to grace + 700ms CI overhead. The 5_000ms pre-fix default
            // would blow past this comfortably (>2× the bound).
            long upperBoundMs = grace.toMillis() + 700L;
            assertTrue(
                       elapsedMs < upperBoundMs,
                       "shutdown() took "
                               + elapsedMs
                               + "ms; expected < "
                               + upperBoundMs
                               + "ms with workerShutdownGrace="
                               + grace);
        } finally {
            // Release the listener so the daemon thread can exit (it's still parked
            // on release.await()) and is otherwise leaked between test runs.
            listener.release.countDown();
            worker.shutdown();
        }
    }
}
