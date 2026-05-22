package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import net.nexus_flow.core.runtime.FlowScope;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import org.junit.jupiter.api.Test;

/**
 * Pins the §11.14 hardening: when a dispatch is interrupted by {@link OutboxWorker#shutdown()}
 * (worker-token cancellation), the row MUST be released back to {@link OutboxStatus#PENDING}
 * <em>without</em> burning an attempt — not marked as {@link OutboxStatus#PENDING} via {@code
 * markFailed} (which increments attempts) and not transitioned to {@link
 * OutboxStatus#FAILED_TERMINAL}.
 *
 * <p>Pre-fix: {@code processOne}'s catch-all called {@code classifyAndMark}, which incremented the
 * attempt counter for what was really a shutdown-induced no-op. Over many graceful restarts the
 * attempt counter would inflate and eventually push the row to {@code FAILED_TERMINAL} purely from
 * shutdown noise.
 *
 * <p>Post-fix: {@code processOne} checks {@code workerToken.isCancellationRequested()} on the catch
 * path. When cancellation is the trigger, it calls {@link OutboxStorage#releaseToReady} instead of
 * {@code classifyAndMark}. The row goes back to {@code PENDING} with {@code attempts} unchanged,
 * ready to be re-claimed on the next startup (or by another replica) without any wasted attempt
 * counter increment.
 */
class OutboxWorkerReleasesRowOnShutdownCancellationTest {

    static final class Pulse extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Pulse(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Heart extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void pulse() {
            recordEvent(new Pulse("heart-release"));
        }
    }

    /**
     * Listener that polls cancellation in a tight loop. Throws {@link FlowCancellationException} as
     * soon as the token fires, so the worker's catch path sees a cancellation-induced failure.
     */
    static final class CooperativeCancellationListener extends AbstractDomainEventListener<Pulse> {
        final CountDownLatch entered = new CountDownLatch(1);

        @Override
        public void handle(Pulse event) {
            entered.countDown();
            ExecutionContext ctx      = FlowScope.current().orElseThrow();
            long             deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                ctx.throwIfCancelledOrExpired();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
            // Should never reach here under the cooperative-cancel contract.
        }
    }

    @Test
    void shutdownCancellationDuringDispatch_releasesRowToPendingWithoutAttemptIncrement() throws Exception {
        Clock                 clock   = Clock.fixed(Instant.parse("2026-05-24T11:00:00Z"), ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec   = new JavaSerializationOutboxPayloadCodec();

        Heart heart = new Heart();
        heart.pulse();
        List<DomainEvent> drained = heart.drainEvents();
        OutboxAppender.appendDrainedEvents(drained, ExecutionContext.root(), storage, clock, codec);

        // Capture the appended row's id so we can inspect its post-shutdown state.
        List<OutboxRecord> snapshot = storage.snapshot();
        assertEquals(1, snapshot.size(), "exactly one row should be appended");
        OutboxId rowId = snapshot.getFirst().outboxId();
        assertEquals(0, snapshot.getFirst().attempts(), "row should start with 0 attempts");

        EventBus                        bus      = EventBus.newInstance();
        CooperativeCancellationListener listener = new CooperativeCancellationListener();
        bus.register(listener);

        OutboxConfig config =
                OutboxConfig.builder(storage, codec)
                        .clock(clock)
                        .useOutboxFanOut(true)
                        .workerPollInterval(Duration.ofMillis(10))
                        .workerBatchSize(1)
                        .workerShutdownGrace(Duration.ofSeconds(2))
                        .build();
        OutboxWorker worker = new OutboxWorker(config, bus, ErrorPolicy.failFast());

        try {
            worker.start();

            // Wait for the worker to deliver the event to the listener.
            assertTrue(
                       listener.entered.await(5, TimeUnit.SECONDS),
                       "listener was never invoked — worker did not deliver");

            // Shut down: worker token is cancelled, listener throws FlowCancellationException,
            // processOne sees workerToken.isCancellationRequested() == true and calls
            // releaseToReady instead of classifyAndMark.
            worker.shutdown();

            OutboxRecord after = storage.findById(rowId);
            assertNotNull(after, "row must still exist post-shutdown");
            assertEquals(
                         OutboxStatus.PENDING,
                         after.status(),
                         "row must be returned to PENDING by releaseToReady (not FAILED_TERMINAL or stuck"
                                 + " IN_FLIGHT)");
            assertEquals(
                         0,
                         after.attempts(),
                         "attempts must NOT be incremented for a shutdown-cancelled dispatch — "
                                 + "shutdown cancellation is a no-op attempt, not a real failure");
            assertNull(after.lastError(), "lastError must remain null for a shutdown-released row");
            assertNull(after.nextRetryAt(), "nextRetryAt must remain cleared so row is re-claimable");
        } finally {
            worker.shutdown();
        }
    }
}
