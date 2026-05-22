package net.nexus_flow.core.inbox;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import net.nexus_flow.core.runtime.ids.MessageId;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link InboxStorage} deduplication is scoped by (messageId, consumerId) pair, not by the
 * domain event's idempotency key. Different messages are always fresh, same message to different
 * consumers is always fresh.
 *
 * <ul>
 * <li>Same {@link MessageId}, same consumer → one Fresh, one Duplicate.
 * <li>Different {@link MessageId}s, same consumer → both Fresh.
 * <li>Same {@link MessageId}, different consumers → both Fresh.
 * </ul>
 */
class MessageIdDedupeIsIndependentFromIdempotencyKeyTest {

    @Test
    void same_messageId_sameConsumer_deduplicates() {
        InboxStorage inbox = new InMemoryInboxStorage();
        MessageId    m     = MessageId.random();
        Instant      t0    = Instant.parse("2026-05-19T12:00:00Z");

        InboxClaim first  = inbox.claimIfNew(m, "consumer-A", t0);
        InboxClaim second = inbox.claimIfNew(m, "consumer-A", t0);

        assertInstanceOf(InboxClaim.Fresh.class, first);
        assertInstanceOf(InboxClaim.Duplicate.class, second);
    }

    @Test
    void different_messageIds_sameConsumer_bothFresh() {
        // Inbox does not consult IdempotencyKey — different MessageIds are always fresh
        InboxStorage inbox = new InMemoryInboxStorage();
        MessageId    m1    = MessageId.random();
        MessageId    m2    = MessageId.random();
        assertNotEquals(m1, m2);
        Instant t0 = Instant.parse("2026-05-19T12:00:00Z");

        InboxClaim claim1 = inbox.claimIfNew(m1, "consumer-A", t0);
        InboxClaim claim2 = inbox.claimIfNew(m2, "consumer-A", t0);

        assertInstanceOf(InboxClaim.Fresh.class, claim1);
        assertInstanceOf(InboxClaim.Fresh.class, claim2);
    }

    @Test
    void same_messageId_differentConsumers_bothFresh() {
        // Deduplication is (messageId, consumerId) — different consumers see fresh
        InboxStorage inbox = new InMemoryInboxStorage();
        MessageId    m     = MessageId.random();
        Instant      t0    = Instant.parse("2026-05-19T12:00:00Z");

        InboxClaim claimA = inbox.claimIfNew(m, "consumer-A", t0);
        InboxClaim claimB = inbox.claimIfNew(m, "consumer-B", t0);

        assertInstanceOf(InboxClaim.Fresh.class, claimA);
        assertInstanceOf(InboxClaim.Fresh.class, claimB);
    }
}
