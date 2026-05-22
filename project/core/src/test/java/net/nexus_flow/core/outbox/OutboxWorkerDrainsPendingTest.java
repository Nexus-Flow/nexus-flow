package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.Serial;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * commit (b) — when an {@link OutboxWorker} drains a {@link OutboxStorage#claimBatch(int, Instant)
 * batch} of PENDING rows, every row is re-published through {@link EventBus#dispatchResult} and
 * transitioned to {@link OutboxStatus#PUBLISHED} on success.
 */
class OutboxWorkerDrainsPendingTest {

    static final class Tick extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Tick(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class TickAgg extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void tick() {
            recordEvent(new Tick("agg-drain"));
        }
    }

    @Test
    void drainOnce_publishesEveryRow_andFlipsStatusToPublished() {
        Instant               t0      = Instant.parse("2026-05-19T12:00:00Z");
        Clock                 clock   = Clock.fixed(t0, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec   = new JavaSerializationOutboxPayloadCodec();

        // Append 3 events directly through the codec-aware path.
        TickAgg agg = new TickAgg();
        agg.tick();
        agg.tick();
        agg.tick();
        List<DomainEvent> drained = agg.drainEvents();
        OutboxAppender.appendDrainedEvents(drained, ExecutionContext.root(), storage, clock, codec);
        assertEquals(3, storage.size());

        // Spin up an event bus and register a listener that records
        // every event it sees by idempotencyKey.
        EventBus     bus  = EventBus.newInstance();
        List<String> seen = new CopyOnWriteArrayList<>();
        bus.register(
                     new AbstractDomainEventListener<Tick>() {
                         @Override
                         public void handle(Tick event) {
                             seen.add(event.idempotencyKey());
                         }
                     });

        OutboxConfig config =
                OutboxConfig.builder(storage, codec)
                        .clock(clock)
                        .useOutboxFanOut(true)
                        .workerPollInterval(Duration.ofMillis(50))
                        .build();
        OutboxWorker worker = new OutboxWorker(config, bus, ErrorPolicy.failFast());

        // Manually drive one drain cycle on the calling thread.
        int processed = worker.drainOnce();
        assertEquals(3, processed, "worker must process every PENDING row");

        // Every event landed at the listener, in recording order
        // (ordering preserved by claimBatch).
        assertEquals(List.of("agg-drain:0", "agg-drain:1", "agg-drain:2"), seen);

        // Every row is now PUBLISHED.
        for (OutboxRecord r : storage.snapshot()) {
            assertEquals(
                         OutboxStatus.PUBLISHED, r.status(), "every row must be PUBLISHED after drain; row=" + r);
            assertEquals(1, r.attempts());
        }

        // A second drain yields nothing — no PENDING rows left.
        assertEquals(0, worker.drainOnce(), "no PENDING rows remain");
    }
}
