package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

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
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import org.junit.jupiter.api.Test;

/**
 * Pins the §11.14 discriminator contract: a {@link FlowCancellationException} thrown by the handler
 * when the worker token is <em>not</em> cancelled is a real business-cancellation and MUST count as
 * a delivery attempt (incrementing {@code attempts} and scheduling a retry through {@code
 * markFailed}).
 *
 * <p>Pre-§11.14, both cases went through {@code classifyAndMark}; post-§11.14 the explicit {@code
 * catch (FlowCancellationException fce)} branch in {@link OutboxWorker#processOne(OutboxRecord)}
 * discriminates:
 *
 * <ul>
 * <li>{@code workerToken.isCancellationRequested() == true} → shutdown-cancellation → {@link
 * OutboxStorage#releaseToReady} (no attempt burned). Pinned by {@link
 * OutboxWorkerReleasesRowOnShutdownCancellationTest}.
 * <li>{@code workerToken.isCancellationRequested() == false} → business-cancellation → {@code
 *       classifyAndMark} (attempt incremented, retry scheduled). Pinned here.
 * </ul>
 *
 * <p>Business-cancellation examples that this branch covers: handler deadline (a per-handler {@code
 * Deadline} watchdog throws {@link FlowCancellationException}), guard predicate aborts (a feature
 * flag flips to "deny" mid-dispatch), saga-compensation handlers that cancel themselves when their
 * state moves to COMPENSATED.
 */
class OutboxWorkerBusinessCancellationCountsAsFailureTest {

    static final class Tick extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Tick(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Heart extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void tick() {
            recordEvent(new Tick("heart-business-cancel"));
        }
    }

    /** Throws {@link FlowCancellationException} unconditionally — simulating a business-cancel. */
    static final class BusinessCancellingListener extends AbstractDomainEventListener<Tick> {
        final CountDownLatch invoked = new CountDownLatch(1);

        @Override
        public void handle(Tick event) {
            invoked.countDown();
            // Handler-driven cancellation WITHOUT shutdown: throw FlowCancellationException directly.
            // The worker token is NOT cancelled, so the discriminator must classify this as a real
            // failure (attempt counter goes up; row is scheduled for retry).
            throw new FlowCancellationException();
        }
    }

    @Test
    void handlerThrowsFCE_workerTokenNotCancelled_isCountedAsAttemptAndScheduledForRetry() throws Exception {
        Clock                 clock   = Clock.systemUTC();
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec   = new JavaSerializationOutboxPayloadCodec();

        Heart heart = new Heart();
        heart.tick();
        List<DomainEvent> drained = heart.drainEvents();
        OutboxAppender.appendDrainedEvents(drained, ExecutionContext.root(), storage, clock, codec);

        List<OutboxRecord> initial = storage.snapshot();
        assertEquals(1, initial.size());
        OutboxId rowId = initial.getFirst().outboxId();
        assertEquals(0, initial.getFirst().attempts(), "row starts with attempts=0");

        EventBus                   bus      = EventBus.newInstance();
        BusinessCancellingListener listener = new BusinessCancellingListener();
        bus.register(listener);

        OutboxConfig config =
                OutboxConfig.builder(storage, codec)
                        .clock(clock)
                        .useOutboxFanOut(true)
                        .workerBatchSize(1)
                        .workerMaxAttempts(5) // plenty of room for retries
                        .autoStartWorker(false) // drive synchronously via drainOnce()
                        .workerShutdownGrace(Duration.ofMillis(500))
                        .build();
        OutboxWorker worker = new OutboxWorker(config, bus, ErrorPolicy.failFast());

        try {
            // Drive exactly one cycle synchronously so the test is deterministic.
            int processed = worker.drainOnce();
            assertEquals(1, processed, "exactly one row processed");
            assertTrue(
                       listener.invoked.await(2, TimeUnit.SECONDS),
                       "listener must have been invoked exactly once");

            OutboxRecord after = storage.findById(rowId);
            assertNotNull(after);
            assertEquals(
                         OutboxStatus.PENDING,
                         after.status(),
                         "business-cancelled row must be PENDING (rescheduled for retry), not FAILED_TERMINAL");
            assertEquals(
                         1,
                         after.attempts(),
                         "business-cancellation MUST count as an attempt — discriminator says workerToken is "
                                 + "NOT cancelled, so this is a real business failure");
            assertNotNull(
                          after.lastError(), "lastError must record the FlowCancellationException stack trace");
            assertTrue(
                       after.lastError().contains("FlowCancellationException"),
                       "lastError must reference FlowCancellationException; got: " + after.lastError());
            assertNotNull(
                          after.nextRetryAt(), "business-failure row must have nextRetryAt set by classifyAndMark");
        } finally {
            worker.shutdown(ShutdownMode.IMMEDIATE);
        }
    }
}
