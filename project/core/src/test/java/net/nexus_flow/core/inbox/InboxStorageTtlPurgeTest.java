package net.nexus_flow.core.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import net.nexus_flow.core.runtime.ids.MessageId;
import org.junit.jupiter.api.Test;

/**
 * Pins the inbox TTL purge contract:
 *
 * <ol>
 * <li>{@link InboxStorage#purgeBefore(Instant)} default throws {@link
 * UnsupportedOperationException} so legacy adapters fail loud when an operator wires a
 * purge worker against them.
 * <li>{@link InMemoryInboxStorage#purgeBefore(Instant)} removes rows whose {@code
 *       firstSeenAt} is strictly before {@code cutoff}, regardless of status (PROCESSING,
 * PROCESSED, FAILED).
 * <li>After a purge the same {@code (messageId, consumerId)} pair claims fresh again —
 * both maps ({@code byId} and {@code byKey}) are coherent.
 * <li>{@code cutoff} of {@code null} is rejected.
 * </ol>
 */
class InboxStorageTtlPurgeTest {

    private static final Instant T0 = Instant.parse("2026-05-28T12:00:00Z");

    @Test
    void defaultImpl_throwsUOE_so_legacyBackends_failLoud() {
        InboxStorage stub = new InboxStorage() {
            @Override
            public InboxClaim claimIfNew(MessageId messageId, String consumerId, Instant now) {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public void markProcessed(InboxId id, Instant now) {
            }

            @Override
            public void markFailed(InboxId id, Instant now) {
            }
        };
        assertThrows(UnsupportedOperationException.class, () -> stub.purgeBefore(T0));
    }

    @Test
    void inMemory_purgesRowsBeforeCutoff_returnsCount() {
        InMemoryInboxStorage storage = new InMemoryInboxStorage();
        storage.claimIfNew(MessageId.random(), "consumer-A", T0.minus(Duration.ofHours(5)));
        storage.claimIfNew(MessageId.random(), "consumer-A", T0.minus(Duration.ofHours(2)));
        storage.claimIfNew(MessageId.random(), "consumer-A", T0.minus(Duration.ofMinutes(30)));

        long removed = storage.purgeBefore(T0.minus(Duration.ofHours(1)));
        assertEquals(2L, removed, "two rows strictly before cutoff MUST be purged");
    }

    @Test
    void inMemory_purgePreservesRowsAtOrAfterCutoff() {
        InMemoryInboxStorage storage = new InMemoryInboxStorage();
        MessageId            kept    = MessageId.random();
        storage.claimIfNew(MessageId.random(), "c", T0.minus(Duration.ofMinutes(5)));
        storage.claimIfNew(kept, "c", T0); // exactly at cutoff — kept (strictly-before semantics)
        storage.claimIfNew(MessageId.random(), "c", T0.plus(Duration.ofMinutes(5)));

        storage.purgeBefore(T0);

        // The kept row is still claimable (a re-claim would return Duplicate, not Fresh).
        InboxClaim re = storage.claimIfNew(kept, "c", T0.plus(Duration.ofHours(1)));
        assertInstanceOf(InboxClaim.Duplicate.class, re,
                         "row at cutoff MUST NOT be purged (strict before semantics)");
    }

    @Test
    void afterPurge_sameMessageId_canClaimFresh() {
        InMemoryInboxStorage storage = new InMemoryInboxStorage();
        MessageId            msg     = MessageId.random();
        storage.claimIfNew(msg, "c", T0.minus(Duration.ofHours(5)));

        storage.purgeBefore(T0);

        // After the purge, the same (msg, "c") pair MUST claim fresh — proves both byId and
        // byKey were coherently removed.
        InboxClaim claim = storage.claimIfNew(msg, "c", T0.plus(Duration.ofHours(1)));
        assertInstanceOf(InboxClaim.Fresh.class, claim,
                         "after purge, the dedup window is open again for the same key");
    }

    @Test
    void purgeBefore_rejectsNullCutoff() {
        InMemoryInboxStorage storage = new InMemoryInboxStorage();
        assertThrows(NullPointerException.class, () -> storage.purgeBefore(null));
    }

    @Test
    void purgeBefore_emptyStorage_returnsZero() {
        InMemoryInboxStorage storage = new InMemoryInboxStorage();
        assertEquals(0L, storage.purgeBefore(T0));
    }

    @Test
    void purgeBefore_purgesAcrossEveryStatus() {
        InMemoryInboxStorage storage = new InMemoryInboxStorage();
        // PROCESSING row (just claimed, never resolved)
        InboxClaim claimP = storage.claimIfNew(MessageId.random(), "c",
                                               T0.minus(Duration.ofHours(5)));
        // PROCESSED row
        InboxClaim claimDone = storage.claimIfNew(MessageId.random(), "c",
                                                  T0.minus(Duration.ofHours(5)));
        storage.markProcessed(((InboxClaim.Fresh) claimDone).id(), T0.minus(Duration.ofHours(4)));
        // FAILED row
        InboxClaim claimFail = storage.claimIfNew(MessageId.random(), "c",
                                                  T0.minus(Duration.ofHours(5)));
        storage.markFailed(((InboxClaim.Fresh) claimFail).id(), T0.minus(Duration.ofHours(4)));

        long removed = storage.purgeBefore(T0.minus(Duration.ofHours(1)));
        assertEquals(3L, removed,
                     "purge MUST remove PROCESSING + PROCESSED + FAILED rows past the cutoff");
        assertTrue(claimP instanceof InboxClaim.Fresh,
                   "sanity — initial PROCESSING claim was Fresh");
    }
}
