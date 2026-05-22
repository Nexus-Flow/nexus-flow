package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * commit (c) — at-least-once guarantee under crash.
 *
 * <p>Scenario:
 *
 * <ol>
 * <li>Worker A claims a batch of N rows (status flipped to {@code IN_FLIGHT}).
 * <li>Worker A successfully calls {@code eventBus.dispatchResult(event, ctx, policy)} for row R₀
 * — the listener observes the event.
 * <li>Worker A crashes BEFORE calling {@code markPublished(R₀)} — R₀ stays {@code IN_FLIGHT} in
 * storage (or, in our simulation, we roll it back to {@code PENDING} so a healthy worker
 * re-claims it; in the JDBC implementation a stale-lease janitor will do the same).
 * <li>Worker B on restart re-claims R₀ and re-delivers it — the listener observes the event
 * AGAIN.
 * </ol>
 *
 * <p>only guarantees at-least-once; the* idempotencyKey is the dedup handle that listeners must
 * apply themselves. This test deliberately uses a non-deduping listener and asserts the listener
 * saw the event TWICE — proving the republish-on-restart path actually runs.
 */
class OutboxWorkerRepublishesOnRestartTest {

    static final class Order extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Order(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Cart extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void order() {
            recordEvent(new Order("cart-crash"));
        }
    }

    @Test
    void crashBetweenDispatchAndMarkPublished_secondWorkerRepublishes() {
        Instant               t0      = Instant.parse("2026-05-19T14:00:00Z");
        Clock                 clock   = Clock.fixed(t0, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec   = new JavaSerializationOutboxPayloadCodec();

        // Append one row.
        Cart c = new Cart();
        c.order();
        List<DomainEvent> drained = c.drainEvents();
        OutboxAppender.appendDrainedEvents(drained, ExecutionContext.root(), storage, clock, codec);

        // (1) Worker A claims the batch — the row flips to IN_FLIGHT.
        List<OutboxRecord> batch = storage.claimBatch(10, t0);
        assertEquals(1, batch.size());
        OutboxRecord claimed = batch.getFirst();
        assertEquals(OutboxStatus.IN_FLIGHT, claimed.status());

        // (2) Worker A successfully publishes to a non-deduping listener.
        List<String> seen = new CopyOnWriteArrayList<>();
        EventBus     bus  = EventBus.newInstance();
        bus.register(
                     new AbstractDomainEventListener<Order>() {
                         @Override
                         public void handle(Order event) {
                             seen.add(event.idempotencyKey());
                         }
                     });
        DomainEvent payload = codec.decode(claimed.payloadBytes(), claimed.payloadType());
        bus.dispatchResult(payload);
        assertEquals(1, seen.size(), "first dispatch must reach the listener exactly once");

        // (3) CRASH SIMULATION — Worker A dies BEFORE calling
        // markPublished. In a real JDBC deployment a janitor would
        // detect the dead lease and reset IN_FLIGHT → PENDING; we do
        // the same here manually via re-append over the row.
        storage.markFailed(
                           claimed.outboxId(),
                           new RuntimeException("worker A crashed"),
                           t0); // immediately eligible for re-claim

        // (4) Worker B restarts, claims again, succeeds, marks published.
        OutboxConfig cfg     =
                OutboxConfig.builder(storage, codec).clock(clock).useOutboxFanOut(true).build();
        OutboxWorker workerB =
                new OutboxWorker(cfg, bus, net.nexus_flow.core.runtime.ErrorPolicy.failFast());
        workerB.drainOnce();

        // (5) At-least-once: the listener saw the event TWICE (once
        // from worker A pre-crash, once from worker B post-restart).
        assertEquals(
                     List.of("cart-crash:0", "cart-crash:0"),
                     seen,
                     "at-least-once delivery: same event reaches the listener twice");
        // And the second attempt finalised the row.
        assertEquals(OutboxStatus.PUBLISHED, storage.findById(claimed.outboxId()).status());
    }
}
