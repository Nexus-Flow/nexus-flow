package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the contract of {@link InMemoryOutboxStorage#releaseToReady(OutboxId)}:
 *
 * <ul>
 * <li>An {@link OutboxStatus#IN_FLIGHT} row transitions back to {@link OutboxStatus#PENDING}.
 * <li>The {@code attempts} counter is NOT incremented (this is the semantic distinction from
 * {@link OutboxStorage#markFailed}).
 * <li>The {@code lastError} / {@code lastAttemptAt} fields are preserved unchanged.
 * <li>The {@code nextRetryAt} field is cleared (the row becomes immediately re-claimable).
 * <li>Calling releaseToReady on a row in any non-IN_FLIGHT state is a no-op (idempotent /
 * defensive).
 * <li>Calling releaseToReady with a {@code null} id throws {@link NullPointerException}.
 * </ul>
 *
 * <p>This is the contract that lets {@link OutboxWorker#shutdown()} hand cancelled-mid-dispatch
 * rows back to the next worker (or the next JVM restart) without burning the attempt counter on
 * what amounts to a shutdown-induced "no-op" attempt.
 */
class InMemoryOutboxStorageReleaseToReadyTest {

    @Test
    void releaseToReady_inFlightRow_goesPendingWithoutBurningAnAttempt() {
        Instant               now     = Instant.parse("2026-05-24T10:00:00Z");
        Clock                 clock   = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        OutboxRecord pending = OutboxFixtures.pending("agg-1", 0, now.minusMillis(10));
        storage.append(pending);
        List<OutboxRecord> claimed = storage.claimBatch(10, now);
        assertEquals(1, claimed.size(), "row must be claimed into IN_FLIGHT");
        OutboxRecord inFlight = claimed.getFirst();
        assertEquals(OutboxStatus.IN_FLIGHT, inFlight.status());
        assertEquals(0, inFlight.attempts(), "row should have zero attempts before release");

        storage.releaseToReady(inFlight.outboxId());

        OutboxRecord after = storage.findById(inFlight.outboxId());
        assertNotNull(after, "row must still exist after releaseToReady");
        assertEquals(OutboxStatus.PENDING, after.status(), "row must go back to PENDING");
        assertEquals(0, after.attempts(), "releaseToReady must NOT increment attempts");
        assertNull(after.lastError(), "lastError must remain null (no failure happened)");
        assertNull(after.lastAttemptAt(), "lastAttemptAt must remain null (no attempt completed)");
        assertNull(after.nextRetryAt(), "nextRetryAt must be cleared so row is immediately eligible");
    }

    @Test
    void releaseToReady_alreadyPendingRow_isNoOp() {
        Instant               now     = Instant.parse("2026-05-24T10:00:00Z");
        Clock                 clock   = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);
        OutboxRecord          pending = OutboxFixtures.pending("agg-1", 0, now.minusMillis(10));
        storage.append(pending);

        storage.releaseToReady(pending.outboxId());

        OutboxRecord after = storage.findById(pending.outboxId());
        assertNotNull(after);
        assertEquals(OutboxStatus.PENDING, after.status());
        assertEquals(pending.attempts(), after.attempts());
    }

    @Test
    void releaseToReady_publishedRow_isNoOp() {
        Instant               now     = Instant.parse("2026-05-24T10:00:00Z");
        Clock                 clock   = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);
        OutboxRecord          pending = OutboxFixtures.pending("agg-1", 0, now.minusMillis(10));
        storage.append(pending);
        storage.claimBatch(10, now);
        storage.markPublished(pending.outboxId());

        storage.releaseToReady(pending.outboxId());

        OutboxRecord after = storage.findById(pending.outboxId());
        assertNotNull(after);
        assertEquals(
                     OutboxStatus.PUBLISHED, after.status(), "releaseToReady must NOT rescue a terminal row");
    }

    @Test
    void releaseToReady_unknownId_isNoOp() {
        Instant               now     = Instant.parse("2026-05-24T10:00:00Z");
        Clock                 clock   = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        storage.releaseToReady(OutboxId.next());
        // No exception, no entry created.
        assertEquals(0, storage.size());
    }

    @Test
    void releaseToReady_nullId_throws() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        assertThrows(NullPointerException.class, () -> storage.releaseToReady(null));
    }
}
