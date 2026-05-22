package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

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
import net.nexus_flow.core.inbox.InMemoryInboxStorage;
import net.nexus_flow.core.inbox.InboxClaim;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * When an {@link OutboxWorker} is configured with an {@link
 * net.nexus_flow.core.inbox.InboxStorage}, every drained row is routed through {@code
 * claimIfNew(messageId, consumerId)} BEFORE dispatch. If the inbox already holds the {@code
 * messageId}, the worker skips the dispatch entirely — the listener is never invoked — yet still
 * marks the outbox row PUBLISHED (the message has effectively been delivered).
 */
class OutboxWorkerDeduplicatesThroughInboxTest {

    static final class Tick extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Tick(String aggId) {
            super(aggId);
        }
    }

    static final class TickAgg extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void tick() {
            recordEvent(new Tick("agg-dedupe"));
        }
    }

    @Test
    void inboxClaimsDuplicate_listenerNeverCalled_outboxStillPublished() {
        Instant t0    = Instant.parse("2026-05-19T12:00:00Z");
        Clock   clock = Clock.fixed(t0, ZoneOffset.UTC);

        InMemoryOutboxStorage outbox = new InMemoryOutboxStorage(clock);
        InMemoryInboxStorage  inbox  = new InMemoryInboxStorage();
        OutboxPayloadCodec    codec  = new JavaSerializationOutboxPayloadCodec();

        // Append one event to the outbox.
        TickAgg agg = new TickAgg();
        agg.tick();
        List<DomainEvent> drained = agg.drainEvents();
        ExecutionContext  ctx     = ExecutionContext.root();
        OutboxAppender.appendDrainedEvents(drained, ctx, outbox, clock, codec);
        assertEquals(1, outbox.size());
        OutboxRecord row = outbox.snapshot().getFirst();

        // Pre-poison the inbox so the worker observes a Duplicate claim
        // for this messageId. Inserting via claimIfNew + markProcessed
        // mirrors the "this consumer already saw and processed M" state
        // that arises when a row is republished after a crash recovery.
        String           consumerId = "outbox-worker";
        InboxClaim       claim      = inbox.claimIfNew(row.messageId(), consumerId, t0);
        InboxClaim.Fresh fresh      = assertInstanceOf(InboxClaim.Fresh.class, claim);
        inbox.markProcessed(fresh.id(), t0);

        // Bus with a listener that records every event.
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
                OutboxConfig.builder(outbox, codec)
                        .clock(clock)
                        .useOutboxFanOut(true)
                        .workerPollInterval(Duration.ofMillis(50))
                        .inbox(inbox)
                        .inboxConsumerId(consumerId)
                        .build();
        OutboxWorker worker = new OutboxWorker(config, bus, ErrorPolicy.failFast());

        int processed = worker.drainOnce();
        assertEquals(
                     1,
                     processed,
                     "the worker still drains the row (it cannot tell a-priori it is a duplicate)");
        // The inbox dedupe short-circuits the dispatch — listener is silent.
        assertTrue(
                   seen.isEmpty(),
                   "listener must NOT see the event when inbox holds the messageId; saw=" + seen);
        // The outbox row is PUBLISHED anyway — the message HAS been delivered;
        // the worker just observed the dedupe receipt.
        OutboxRecord after = outbox.snapshot().getFirst();
        assertEquals(
                     OutboxStatus.PUBLISHED,
                     after.status(),
                     "outbox row must transition to PUBLISHED even when inbox dedupes");
    }
}
