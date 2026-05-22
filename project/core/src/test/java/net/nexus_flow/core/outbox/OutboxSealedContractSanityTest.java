package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * commit (a) — sanity checks for the outbox contract and its companion value types. The original
 * sealed-permits assertion was retired together with the {@code JdbcOutboxStorage} placeholder in
 * {@link OutboxStorage} is now a regular interface so the production JDBC backend lives in {@code
 * nexus-flow-jdbc} without touching {@code core}.
 *
 * <ul>
 * <li>{@link OutboxStorage} is reachable from {@link InMemoryOutboxStorage}.
 * <li>{@link OutboxId#next()} is monotonic within a JVM.
 * <li>{@link IdempotencyKey} validates non-blank input.
 * <li>{@link OutboxStatus} carries the four canonical states.
 * </ul>
 */
class OutboxSealedContractSanityTest {

    @Test
    void outboxStorage_isAssignableFromInMemoryBackend() {
        assertTrue(
                   OutboxStorage.class.isAssignableFrom(InMemoryOutboxStorage.class),
                   "InMemoryOutboxStorage must remain an OutboxStorage implementation");
    }

    @Test
    void outboxIdNext_isMonotonicWithinJvm() {
        OutboxId a = OutboxId.next();
        OutboxId b = OutboxId.next();
        OutboxId c = OutboxId.next();
        assertTrue(a.compareTo(b) < 0, "a < b expected");
        assertTrue(b.compareTo(c) < 0, "b < c expected");
        assertNotEquals(a, b);
        assertNotEquals(b, c);
    }

    @Test
    void idempotencyKey_rejectsNullAndBlank() {
        assertThrows(NullPointerException.class, () -> new IdempotencyKey(null));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyKey(""));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyKey(" "));
    }

    @Test
    void outboxStatus_hasTheFourCanonicalStates() {
        Set<OutboxStatus> all = Set.of(OutboxStatus.values());
        assertEquals(
                     Set.of(
                            OutboxStatus.PENDING,
                            OutboxStatus.IN_FLIGHT,
                            OutboxStatus.PUBLISHED,
                            OutboxStatus.FAILED_TERMINAL),
                     all);
    }
}
