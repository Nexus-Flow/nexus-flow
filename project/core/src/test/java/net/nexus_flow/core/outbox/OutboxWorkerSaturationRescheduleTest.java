package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.cqrs.command.SaturationPolicy;
import net.nexus_flow.core.cqrs.command.SaturationRejectedException;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * commit (b) — when an {@link OutboxWorker} drains a row and the listener fails with a* {@link
 * SaturationRejectedException}, the row MUST be marked failed (NOT terminal) and re-scheduled with
 * a back-off so a later drain picks it up again.
 */
class OutboxWorkerSaturationRescheduleTest {

    static final class Push extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Push(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Topic extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void push() {
            recordEvent(new Push("topic-1"));
        }
    }

    /** Test clock we can advance manually to simulate the back-off window. */
    static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant start) {
            this.now = start;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    @Test
    void firstAttempt_saturationRejected_rowIsRescheduled_secondAttemptPublishes() {
        TestClock             clock   = new TestClock(Instant.parse("2026-05-19T13:00:00Z"));
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec   = new JavaSerializationOutboxPayloadCodec();

        Topic agg = new Topic();
        agg.push();
        List<DomainEvent> drained = agg.drainEvents();
        OutboxAppender.appendDrainedEvents(drained, ExecutionContext.root(), storage, clock, codec);

        // Listener that throws SaturationRejected on first delivery and
        // succeeds on the retry.
        AtomicBoolean shouldReject = new AtomicBoolean(true);
        AtomicInteger deliveries   = new AtomicInteger();
        EventBus      bus          = EventBus.newInstance();
        bus.register(
                     new AbstractDomainEventListener<Push>() {
                         @Override
                         public void handle(Push event) {
                             deliveries.incrementAndGet();
                             if (shouldReject.getAndSet(false)) {
                                 throw new SaturationRejectedException(
                                         Push.class, 1, SaturationPolicy.REJECT, ExecutionContext.root());
                             }
                         }
                     });

        OutboxConfig config =
                OutboxConfig.builder(storage, codec)
                        .clock(clock)
                        .useOutboxFanOut(true)
                        .workerPollInterval(Duration.ofMillis(50))
                        .workerMaxAttempts(5)
                        .build();
        OutboxWorker worker = new OutboxWorker(config, bus, ErrorPolicy.failFast());

        // (1) First drain — the listener rejects, the row should be
        // marked failed with a future nextRetryAt and attempts=1.
        worker.drainOnce();
        OutboxRecord afterFirst = storage.snapshot().getFirst();
        assertEquals(
                     OutboxStatus.PENDING,
                     afterFirst.status(),
                     "saturation reject must reschedule (PENDING), not mark terminal");
        assertEquals(1, afterFirst.attempts());
        assertNotNull(afterFirst.nextRetryAt(), "saturation reject must schedule nextRetryAt");
        assertTrue(
                   afterFirst.nextRetryAt().isAfter(clock.instant()),
                   "nextRetryAt must be in the future relative to the failure instant");
        assertNotNull(
                      afterFirst.lastError(), "saturation reject must flatten the cause into lastError");
        assertTrue(
                   afterFirst.lastError().contains("SaturationRejectedException"),
                   "lastError must mention the rejection class");

        // (2) Before the backoff elapses, the row is NOT eligible for
        // re-claim — the worker sees an empty batch.
        assertEquals(0, worker.drainOnce(), "row is not eligible while nextRetryAt is in the future");

        // (3) Advance the clock past the backoff and drain again — the
        // listener accepts, the row flips to PUBLISHED.
        clock.advance(Duration.ofSeconds(60));
        worker.drainOnce();
        OutboxRecord afterSecond = storage.snapshot().getFirst();
        assertEquals(OutboxStatus.PUBLISHED, afterSecond.status());
        assertEquals(
                     2,
                     afterSecond.attempts(),
                     "attempts increments on every transition (1 failed + 1 published = 2)");
        assertEquals(2, deliveries.get(), "listener observed two deliveries: one reject + one publish");
    }
}
