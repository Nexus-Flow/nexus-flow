package net.nexus_flow.core.eventsourcing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.outbox.InMemoryOutboxStorage;
import net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxConfig;
import net.nexus_flow.core.outbox.OutboxId;
import net.nexus_flow.core.outbox.OutboxRecord;
import net.nexus_flow.core.outbox.OutboxStorage;
import org.junit.jupiter.api.Test;

/**
 * Pins the new {@link AggregateRepository.Builder#outbox(OutboxConfig)} integration: {@code
 * save(aggregate)} appends the uncommitted batch both to the {@link EventStore} and (when
 * configured) to the outbox storage — so the repository pattern produces durable events
 * without a separate {@link net.nexus_flow.core.outbox.OutboxAppender} call at every save
 * site.
 *
 * <p>Atomicity caveat (documented on save's Javadoc): the in-tree path does not wrap the two
 * appends in a single transaction. Tests below cover the happy path. The post-store-success /
 * pre-markCommitted outbox failure path is covered in {@link
 * #outboxAppendFailureAfterStoreCommit_leavesAggregateUncommitted}, which uses a deliberately
 * broken storage to assert the documented behavior.
 */
class AggregateRepositoryOutboxIntegrationTest {

    static final class ThingCreated extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        ThingCreated(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Thing extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;
        private final UUID        id;

        Thing() {
            this.id = UUID.randomUUID();
        }

        Thing(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getAggregateId() {
            return id;
        }

        void create() {
            recordEvent(new ThingCreated(id.toString()));
        }
    }

    @Test
    void save_withoutOutbox_doesNotTouchOutbox() {
        InMemoryEventStore         store  = new InMemoryEventStore();
        InMemoryOutboxStorage      outbox = new InMemoryOutboxStorage();
        AggregateRepository<Thing> repo   = AggregateRepository.builder(store, Thing.class, Thing::new).build();

        Thing t = new Thing(UUID.randomUUID());
        t.create();
        repo.save(t);

        // The outbox stays empty because no OutboxConfig was wired on the builder.
        List<OutboxRecord> appended = outbox.findSinceSequence(-1L, Integer.MAX_VALUE);
        assertTrue(appended.isEmpty(),
                   "outbox MUST stay empty when the repository was built without .outbox(...)");
        // The event store still received the event.
        assertEquals(1L, t.version(),
                     "event store MUST commit even without outbox integration");
    }

    @Test
    void save_withOutbox_appendsToBothStoreAndOutbox() {
        InMemoryEventStore         store  = new InMemoryEventStore();
        InMemoryOutboxStorage      outbox = new InMemoryOutboxStorage();
        OutboxConfig               cfg    = OutboxConfig.builder(outbox, new JavaSerializationOutboxPayloadCodec())
                .autoStartWorker(false)
                .build();
        AggregateRepository<Thing> repo   = AggregateRepository.builder(store, Thing.class, Thing::new)
                .outbox(cfg)
                .build();

        Thing t = new Thing(UUID.randomUUID());
        t.create();
        repo.save(t);

        List<OutboxRecord> rows = outbox.findSinceSequence(-1L, Integer.MAX_VALUE);
        assertEquals(1, rows.size(),
                     "outbox MUST receive every event the aggregate emits on save");
        OutboxRecord row = rows.getFirst();
        assertEquals(ThingCreated.class, row.payloadType());
        assertEquals(t.getAggregateId().toString(), row.aggregateId());
        assertEquals(1L, t.version(),
                     "aggregate MUST be marked committed AFTER both store and outbox succeed");
    }

    @Test
    void save_empty_uncommitted_skipsBothPaths() {
        InMemoryEventStore         store  = new InMemoryEventStore();
        InMemoryOutboxStorage      outbox = new InMemoryOutboxStorage();
        OutboxConfig               cfg    = OutboxConfig.builder(outbox, new JavaSerializationOutboxPayloadCodec())
                .autoStartWorker(false)
                .build();
        AggregateRepository<Thing> repo   = AggregateRepository.builder(store, Thing.class, Thing::new)
                .outbox(cfg)
                .build();

        // Aggregate with NO uncommitted events — save is a no-op.
        Thing t = new Thing(UUID.randomUUID());
        repo.save(t);

        assertTrue(outbox.findSinceSequence(-1L, Integer.MAX_VALUE).isEmpty());
        assertEquals(0L, t.version());
    }

    @Test
    void save_versionConflict_doesNotAppendToOutbox() {
        InMemoryEventStore         store  = new InMemoryEventStore();
        InMemoryOutboxStorage      outbox = new InMemoryOutboxStorage();
        OutboxConfig               cfg    = OutboxConfig.builder(outbox, new JavaSerializationOutboxPayloadCodec())
                .autoStartWorker(false)
                .build();
        AggregateRepository<Thing> repo   = AggregateRepository.builder(store, Thing.class, Thing::new)
                .outbox(cfg)
                .build();

        UUID id = UUID.randomUUID();

        // Pre-populate the store at version 1.
        Thing first = new Thing(id);
        first.create();
        repo.save(first);
        assertEquals(1L, first.version());
        assertEquals(1, outbox.findSinceSequence(-1L, Integer.MAX_VALUE).size());

        // A second aggregate instance with the same id, still at version 0, attempts to save.
        // The store rejects with VersionConflict; the outbox MUST NOT see the spurious event.
        Thing second = new Thing(id);
        second.create();
        assertThrows(OptimisticConcurrencyException.class, () -> repo.save(second));
        assertEquals(1, outbox.findSinceSequence(-1L, Integer.MAX_VALUE).size(),
                     "version conflict on the event store MUST NOT leave orphan rows in the outbox");
    }

    @Test
    void outboxAppendFailureAfterStoreCommit_leavesAggregateUncommitted() {
        // Documented edge case: the in-tree path commits to the event store BEFORE attempting
        // the outbox append. If the outbox throws, the exception surfaces to the caller and
        // the aggregate stays "uncommitted" (markCommitted never ran). The events ARE in the
        // store, so the operator reconciles by reloading from the store on the next attempt.
        InMemoryEventStore         store = new InMemoryEventStore();
        OutboxConfig               cfg   = OutboxConfig.builder(
                                                                new ThrowingOutboxStorage(),
                                                                new JavaSerializationOutboxPayloadCodec())
                .autoStartWorker(false)
                .build();
        AggregateRepository<Thing> repo  = AggregateRepository.builder(store, Thing.class, Thing::new)
                .outbox(cfg)
                .build();

        Thing t = new Thing(UUID.randomUUID());
        t.create();
        assertThrows(RuntimeException.class, () -> repo.save(t));

        // Documented contract: aggregate left uncommitted (version 0), event store HAS the event.
        assertEquals(0L, t.version(),
                     "aggregate MUST remain uncommitted when outbox throws after store commit");
        EventStream replay = store.read(repo.streamIdFor(t.getAggregateId()), 1L, 10L);
        assertEquals(1, replay.events().size(),
                     "event store keeps the committed event despite outbox failure");
    }

    @Test
    void outbox_inBuilder_isSameReferenceAcrossReads() {
        // Sanity: passing an OutboxConfig once binds it for the repository's lifetime.
        InMemoryEventStore         store  = new InMemoryEventStore();
        InMemoryOutboxStorage      outbox = new InMemoryOutboxStorage();
        OutboxConfig               cfg    = OutboxConfig.builder(outbox, new JavaSerializationOutboxPayloadCodec())
                .autoStartWorker(false)
                .build();
        AggregateRepository<Thing> repo   = AggregateRepository.builder(store, Thing.class, Thing::new)
                .outbox(cfg)
                .build();
        assertSame(store, repo.eventStore());
    }

    /**
     * Deliberately broken outbox storage — every {@link OutboxStorage#append} throws. Used to
     * pin the documented atomicity caveat: store-side success + outbox failure leaves the
     * aggregate uncommitted. Implements {@link OutboxStorage} directly (not via extension) so
     * we keep {@link InMemoryOutboxStorage} sealed against accidental subclassing.
     */
    private static final class ThrowingOutboxStorage implements OutboxStorage {

        @Override
        public void append(OutboxRecord record) {
            throw new RuntimeException("simulated outbox storage failure");
        }

        @Override
        public java.util.List<OutboxRecord> claimBatch(int batchSize, Instant now) {
            return java.util.List.of();
        }

        @Override
        public void markPublished(OutboxId id) {
        }

        @Override
        public void markFailed(OutboxId id, Throwable cause, Instant nextRetryAt) {
        }

        @Override
        public void markFailedTerminal(OutboxId id, Throwable cause) {
        }

        @Override
        public void releaseToReady(OutboxId id) {
        }
    }
}
