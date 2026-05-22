package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * Pins the safety contract of {@link InMemoryOutboxStorage#purgePublishedOlderThan(Instant)}:
 *
 * <ul>
 * <li>Only {@link OutboxStatus#PUBLISHED} rows older than the cutoff are removed.
 * <li>{@code PENDING}, {@code IN_FLIGHT}, and {@code FAILED_TERMINAL} rows are NEVER deleted —
 * deleting unprocessed work or pending replay candidates would be the worst class of
 * operator-tooling bug.
 * <li>The cutoff is exclusive: a row recorded exactly at {@code olderThan} survives.
 * <li>After a purge, the {@code idempotencyKey → outboxId} index is also cleaned so a future
 * re-append of the same key is a fresh insert, not a "duplicate" rejection.
 * <li>Calling with {@code null} fails fast.
 * </ul>
 */
class InMemoryOutboxStoragePurgeTest {

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
        private final UUID        id;

        TickAgg(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getAggregateId() {
            return id;
        }

        void tick() {
            recordEvent(new Tick(id.toString()));
        }
    }

    private static OutboxRecord append(InMemoryOutboxStorage storage, Clock clock, String aggId) {
        TickAgg agg = new TickAgg(UUID.randomUUID());
        agg.tick();
        OutboxRecord row =
                OutboxAppender.toRecord(
                                        agg.drainEvents().getFirst(),
                                        ExecutionContext.root(),
                                        clock,
                                        new JavaSerializationOutboxPayloadCodec());
        // Re-stamp the aggregate-id so the test can craft distinct rows easily.
        OutboxRecord stamped =
                new OutboxRecord(
                        row.outboxId(),
                        IdempotencyKey.of(aggId + ":0"),
                        row.aggregateType(),
                        aggId,
                        row.sequenceNo(),
                        row.traceId(),
                        row.correlationId(),
                        row.causationId(),
                        row.messageId(),
                        row.payloadType(),
                        row.payloadBytes(),
                        row.recordedAt(),
                        row.status(),
                        row.attempts(),
                        row.lastError(),
                        row.lastAttemptAt(),
                        row.nextRetryAt(),
                        row.tenantId(),
                        row.codecId());
        storage.append(stamped);
        return stamped;
    }

    @Test
    void purge_removesOnlyPublishedRows_olderThanCutoff() {
        Instant               t0      = Instant.parse("2026-05-01T00:00:00Z");
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(Clock.fixed(t0, ZoneOffset.UTC));

        // Append three rows at t0, t0+1d, t0+2d.
        OutboxRecord r0 = append(storage, Clock.fixed(t0, ZoneOffset.UTC), "agg-0");
        OutboxRecord r1 =
                append(storage, Clock.fixed(t0.plus(Duration.ofDays(1)), ZoneOffset.UTC), "agg-1");
        OutboxRecord r2 =
                append(storage, Clock.fixed(t0.plus(Duration.ofDays(2)), ZoneOffset.UTC), "agg-2");

        // Mark all PUBLISHED.
        storage.claimBatch(10, t0.plus(Duration.ofDays(3)));
        storage.markPublished(r0.outboxId());
        storage.markPublished(r1.outboxId());
        storage.markPublished(r2.outboxId());

        // Cutoff at t0+1d+1s — should purge r0 only (r1 is at exactly t0+1d so > cutoff; r2 newer).
        Instant cutoff  = t0.plus(Duration.ofDays(1)).plusSeconds(1);
        int     removed = storage.purgePublishedOlderThan(cutoff);
        assertEquals(2, removed, "r0 (t0) AND r1 (t0+1d) are strictly < cutoff; r2 (t0+2d) survives");

        List<OutboxRecord> remaining = storage.snapshot();
        assertEquals(1, remaining.size());
        assertEquals(r2.outboxId(), remaining.getFirst().outboxId());
    }

    @Test
    void purge_neverRemovesPending_orInFlight_orFailedTerminalRows() {
        Instant               t0      = Instant.parse("2026-05-01T00:00:00Z");
        Clock                 clock   = Clock.fixed(t0, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        OutboxRecord pending  = append(storage, clock, "agg-pending");
        OutboxRecord inFlight = append(storage, clock, "agg-inflight");
        OutboxRecord toFail   = append(storage, clock, "agg-failed");

        // Claim — flips the chosen rows to IN_FLIGHT.
        storage.claimBatch(10, t0.plusSeconds(1));
        // For the IN_FLIGHT one, do nothing further (it stays IN_FLIGHT).
        // For the FAILED_TERMINAL one, mark it terminal.
        storage.markFailedTerminal(toFail.outboxId(), new RuntimeException("simulated terminal"));
        // The "pending" row was also claimed and is currently IN_FLIGHT. Release it back to PENDING.
        storage.releaseToReady(pending.outboxId());

        // Purge in the FAR future — every row is older than that.
        int removed = storage.purgePublishedOlderThan(t0.plus(Duration.ofDays(365)));
        assertEquals(
                     0, removed, "purge MUST NEVER remove non-PUBLISHED rows even when the cutoff is unbounded");

        // All three rows are still present.
        assertEquals(3, storage.snapshot().size());
        assertEquals(OutboxStatus.PENDING, storage.findById(pending.outboxId()).status());
        assertEquals(OutboxStatus.IN_FLIGHT, storage.findById(inFlight.outboxId()).status());
        assertEquals(OutboxStatus.FAILED_TERMINAL, storage.findById(toFail.outboxId()).status());
    }

    @Test
    void purge_cutoffIsExclusive_rowAtExactlyCutoffSurvives() {
        Instant               t0      = Instant.parse("2026-05-01T00:00:00Z");
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(Clock.fixed(t0, ZoneOffset.UTC));
        OutboxRecord          r       = append(storage, Clock.fixed(t0, ZoneOffset.UTC), "agg-edge");
        storage.claimBatch(10, t0.plusSeconds(1));
        storage.markPublished(r.outboxId());

        // Cutoff == r.recordedAt — exclusive, so r survives.
        int removed = storage.purgePublishedOlderThan(r.recordedAt());
        assertEquals(0, removed);
        assertNotNull(storage.findById(r.outboxId()));

        // Cutoff one nano after r.recordedAt — strict less-than holds.
        removed = storage.purgePublishedOlderThan(r.recordedAt().plusNanos(1));
        assertEquals(1, removed);
    }

    @Test
    void purge_cleansIdempotencyKeyIndex_soSameKeyCanBeReAppendedFresh() {
        Instant               t0      = Instant.parse("2026-05-01T00:00:00Z");
        Clock                 clock   = Clock.fixed(t0, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);
        OutboxRecord          first   = append(storage, clock, "agg-X");
        storage.claimBatch(10, t0.plusSeconds(1));
        storage.markPublished(first.outboxId());

        storage.purgePublishedOlderThan(t0.plus(Duration.ofDays(365)));
        assertEquals(0, storage.snapshot().size(), "purge removed the published row");

        // Re-append with the same idempotency key — must succeed (fresh insert, no duplicate error).
        OutboxRecord second = append(storage, clock, "agg-X");
        assertNotNull(storage.findById(second.outboxId()));
        // First and second have different outboxIds (fresh OutboxId.next()) and the same idempotency
        // key.
        assertEquals(first.idempotencyKey(), second.idempotencyKey());
    }

    @Test
    void purge_rejectsNullCutoff() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        assertThrows(NullPointerException.class, () -> storage.purgePublishedOlderThan(null));
    }
}
