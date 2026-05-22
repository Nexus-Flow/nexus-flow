package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * commit (d) — a row that hit {@link OutboxStatus#FAILED_TERMINAL} is never re-claimed by the
 * worker, but an operator can resurrect it by appending a fresh row with the same {@link
 * IdempotencyKey} (manual replay path documented in {@link OutboxStorage#append(OutboxRecord)}
 * javadoc).
 */
class OutboxWorkerManualReplayTerminalTest {

    static final class Failed extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Failed(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Stuck extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void fail() {
            recordEvent(new Failed("stuck-1"));
        }
    }

    @Test
    void manualReplay_resurrectsFailedTerminalRow_andSecondAttemptSucceeds() {
        Instant               t0      = Instant.parse("2026-05-19T16:00:00Z");
        Clock                 clock   = Clock.fixed(t0, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec   = new JavaSerializationOutboxPayloadCodec();

        // Append one row.
        Stuck s = new Stuck();
        s.fail();
        List<DomainEvent> drained = s.drainEvents();
        OutboxAppender.appendDrainedEvents(drained, ExecutionContext.root(), storage, clock, codec);

        // A listener that fails once then succeeds.
        AtomicBoolean firstCallFailing = new AtomicBoolean(true);
        EventBus      bus              = EventBus.newInstance();
        bus.register(
                     new AbstractDomainEventListener<Failed>() {
                         @Override
                         public void handle(Failed event) {
                             if (firstCallFailing.getAndSet(false)) {
                                 throw new RuntimeException("first attempt fails");
                             }
                         }
                     });

        // Configure the worker so the very first retry IS the cap →
        // after one failure we go straight to FAILED_TERMINAL.
        OutboxConfig cfg    =
                OutboxConfig.builder(storage, codec)
                        .clock(clock)
                        .useOutboxFanOut(true)
                        .workerMaxAttempts(1)
                        .build();
        OutboxWorker worker = new OutboxWorker(cfg, bus, ErrorPolicy.failFast());

        worker.drainOnce();
        OutboxRecord afterFirst = storage.snapshot().getFirst();
        assertEquals(
                     OutboxStatus.FAILED_TERMINAL,
                     afterFirst.status(),
                     "single attempt over the cap must land at FAILED_TERMINAL");

        // A second drain finds nothing — FAILED_TERMINAL is never claimed.
        assertEquals(0, worker.drainOnce(), "FAILED_TERMINAL rows must not be re-claimed");

        // Manual replay path: re-append the same idempotencyKey. The
        // storage transparently overwrites the FAILED_TERMINAL row.
        // We re-record the same event on a fresh aggregate to get the
        // identical key "stuck-1:0".
        Stuck s2 = new Stuck();
        s2.fail();
        List<DomainEvent> redrained = s2.drainEvents();
        OutboxAppender.appendDrainedEvents(redrained, ExecutionContext.root(), storage, clock, codec);

        // Re-drain — the listener no longer throws → PUBLISHED.
        worker.drainOnce();
        OutboxRecord afterReplay =
                storage.snapshot().stream()
                        .filter(r -> "stuck-1:0".equals(r.idempotencyKey().value()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                     OutboxStatus.PUBLISHED,
                     afterReplay.status(),
                     "manual replay then second attempt must reach PUBLISHED");
    }
}
