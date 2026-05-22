package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * commit (c) — companion to the crash-restart test. The bus delivers a duplicate (at-least-once); a
 * listener that needs exactly-once-effective semantics MUST deduplicate by {@link
 * DomainEvent#idempotencyKey()}.
 *
 * <p>Pattern shown here is the canonical one: a listener-local {@link Set} of seen keys gates the
 * side effect. Production listeners typically push the set down into the same database transaction
 * as the side effect to make dedup transactional.
 */
class OutboxWorkerListenerIdempotencyDedupeTest {

    static final class Charge extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Charge(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Wallet extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void charge() {
            recordEvent(new Charge("wallet-9"));
        }
    }

    /**
     * Listener that records every delivery for diagnostic purposes but only applies its side effect
     * once per {@link DomainEvent#idempotencyKey()}. Thecontract calls this "dedup at the listener
     * boundary".
     */
    static final class DedupingChargeListener extends AbstractDomainEventListener<Charge> {
        private final Set<String> seenKeys           = Collections.synchronizedSet(new HashSet<>());
        final AtomicInteger       deliveries         = new AtomicInteger();
        final AtomicInteger       appliedSideEffects = new AtomicInteger();

        @Override
        public void handle(Charge event) {
            deliveries.incrementAndGet();
            if (seenKeys.add(event.idempotencyKey())) {
                // First time we see this key — apply the effect.
                appliedSideEffects.incrementAndGet();
            }
            // Second+ deliveries are silently dropped.
        }
    }

    @Test
    void duplicateDeliveryViaWorkerRetry_listenerAppliesEffectExactlyOnce() {
        Instant               t0      = Instant.parse("2026-05-19T15:00:00Z");
        Clock                 clock   = Clock.fixed(t0, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec   = new JavaSerializationOutboxPayloadCodec();

        // Single event in the outbox.
        Wallet w = new Wallet();
        w.charge();
        List<DomainEvent> drained = w.drainEvents();
        OutboxAppender.appendDrainedEvents(drained, ExecutionContext.root(), storage, clock, codec);

        DedupingChargeListener listener = new DedupingChargeListener();
        EventBus               bus      = EventBus.newInstance();
        bus.register(listener);

        // Simulate the at-least-once shape: deliver the same event
        // twice through the bus (as would happen across a worker
        // restart between dispatchResult and markPublished).
        OutboxRecord row      = storage.snapshot().getFirst();
        DomainEvent  decoded1 = codec.decode(row.payloadBytes(), row.payloadType());
        DomainEvent  decoded2 = codec.decode(row.payloadBytes(), row.payloadType());
        bus.dispatchResult(decoded1);
        bus.dispatchResult(decoded2);

        // Both deliveries reached the listener — at-least-once was
        // observed by the bus layer.
        assertEquals(
                     2, listener.deliveries.get(), "bus delivered the event twice (at-least-once contract)");

        // But the listener's side effect ran ONCE —// idempotencyKey was enough to dedup.
        assertEquals(
                     1,
                     listener.appliedSideEffects.get(),
                     "idempotencyKey must be sufficient for "
                             + "exactly-once-effective at the listener boundary");

        // Sanity: even after a worker.drainOnce() the side effect
        // count stays at 1.
        OutboxConfig cfg    =
                OutboxConfig.builder(storage, codec).clock(clock).useOutboxFanOut(true).build();
        OutboxWorker worker = new OutboxWorker(cfg, bus, ErrorPolicy.failFast());
        worker.drainOnce();
        assertEquals(
                     1,
                     listener.appliedSideEffects.get(),
                     "worker.drainOnce must not break the dedup invariant");
        // Diagnostic: deliveries went up to 3 (two manual + one via worker).
        assertEquals(3, listener.deliveries.get());

        // The deduping list is for diagnostic ergonomics — not part of
        // the contract.
        List<String> ignored = new CopyOnWriteArrayList<>();
        ignored.add("documentation-only");
    }
}
