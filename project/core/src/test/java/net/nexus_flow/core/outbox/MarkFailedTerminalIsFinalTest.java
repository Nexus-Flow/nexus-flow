package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * commit (c) — {@link OutboxStorage#markFailedTerminal(OutboxId, Throwable)} flips the row to
 * {@link OutboxStatus#FAILED_TERMINAL}; subsequent claim/publish/fail operations are forbidden.
 */
class MarkFailedTerminalIsFinalTest {

    @Test
    void markFailedTerminal_makesRowInvisibleToClaimAndForbidsFurtherTransitions() {
        Instant               now     = Instant.parse("2026-05-19T15:00:00Z");
        Clock                 clock   = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        storage.append(OutboxFixtures.pending("agg-doomed", 0, now.minusSeconds(1)));
        List<OutboxRecord> claimed = storage.claimBatch(1, now);
        OutboxId           id      = claimed.getFirst().outboxId();

        storage.markFailedTerminal(id, new IllegalStateException("poison pill"));

        OutboxRecord row = storage.findById(id);
        assertNotNull(row);
        assertEquals(OutboxStatus.FAILED_TERMINAL, row.status());
        assertEquals(1, row.attempts());
        assertEquals(now, row.lastAttemptAt());

        // Subsequent claims must NOT see the row.
        assertEquals(0, storage.claimBatch(10, now.plusSeconds(86_400)).size());

        // Subsequent publish/fail/terminal calls must throw.
        assertThrows(IllegalOutboxTransitionException.class, () -> storage.markPublished(id));
        assertThrows(
                     IllegalOutboxTransitionException.class,
                     () -> storage.markFailed(id, new RuntimeException(), now.plusSeconds(60)));
        assertThrows(
                     IllegalOutboxTransitionException.class,
                     () -> storage.markFailedTerminal(id, new RuntimeException()));
    }

    @Test
    void markPublishedOnUnknownId_throws() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        assertThrows(
                     IllegalOutboxTransitionException.class, () -> storage.markPublished(OutboxId.next()));
    }

    @Test
    void terminalRowCanBeOverwrittenByManualReplay_appendingNewKeyVersion() {
        Instant               now     = Instant.parse("2026-05-19T16:00:00Z");
        Clock                 clock   = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        OutboxRecord initial = OutboxFixtures.pending("agg-replay", 0, now.minusSeconds(2));
        storage.append(initial);
        storage.claimBatch(1, now);
        storage.markFailedTerminal(initial.outboxId(), new RuntimeException("nope"));

        // Re-append with the same idempotencyKey: must succeed (manual replay).
        OutboxRecord resurrected = OutboxFixtures.pending("agg-replay", 0, now);
        storage.append(resurrected);
        assertEquals(OutboxStatus.PENDING, storage.findById(resurrected.outboxId()).status());
    }
}
