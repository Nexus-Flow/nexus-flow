package net.nexus_flow.core.runtime.ids;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.query.Query;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.inbox.InboxId;
import net.nexus_flow.core.saga.SagaId;
import net.nexus_flow.core.scheduling.ScheduledCommandId;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire-format equivalence between {@link java.util.UUID#randomUUID()} and the framework's
 * fast-path {@code MessageId.random()} / {@code TraceId.random()} / {@code CorrelationId.random()}.
 *
 * <p>Since these id types are observability handles (not security tokens), the framework swapped
 * the SecureRandom-backed {@code UUID.randomUUID()} for a ThreadLocalRandom-backed v4 UUID
 * generator (~10× cheaper on modern JDKs). This regression verifies the swap did not break the UUID
 * wire format:
 *
 * <ul>
 * <li>UUID version nibble is {@code 4} (RFC 4122 v4).
 * <li>UUID variant is {@code IETF (2)}.
 * <li>Two calls produce different UUIDs (uniqueness sanity).
 * <li>10 000 generated UUIDs are pairwise distinct (collision sanity at moderate scale).
 * <li>{@code toString()} matches the canonical 8-4-4-4-12 format that operators / log readers /
 * distributed-tracing consumers expect.
 * </ul>
 */
class FastUuidV4FormatTest {

    @Test
    void messageIdRandom_returnsV4Uuid() {
        MessageId id = MessageId.random();
        assertEquals(4, id.value().version(), "MessageId must be a v4 UUID");
        assertEquals(2, id.value().variant(), "MessageId must use the IETF variant (2)");
    }

    @Test
    void traceIdRandom_returnsV4Uuid() {
        TraceId id = TraceId.random();
        assertEquals(4, id.value().version());
        assertEquals(2, id.value().variant());
    }

    @Test
    void correlationIdRandom_returnsV4Uuid() {
        CorrelationId id = CorrelationId.random();
        assertEquals(4, id.value().version());
        assertEquals(2, id.value().variant());
    }

    @Test
    void twoConsecutiveGenerations_areDistinct() {
        MessageId a = MessageId.random();
        MessageId b = MessageId.random();
        assertNotEquals(a, b, "consecutive MessageId.random() calls must produce distinct ids");
    }

    @Test
    void tenThousandGenerations_arePairwiseDistinct() {
        int            n    = 10_000;
        Set<MessageId> seen = new HashSet<>(n * 2);
        for (int i = 0; i < n; i++) {
            seen.add(MessageId.random());
        }
        assertEquals(
                     n,
                     seen.size(),
                     "10k generated MessageIds must be pairwise distinct (collision in <2^122 space"
                             + " is astronomically unlikely)");
    }

    @Test
    void fastUuidV4_directCall_returnsValidV4() {
        UUID u = FastUuid.v4();
        assertEquals(4, u.version(), "FastUuid.v4() must produce a v4 UUID");
        assertEquals(2, u.variant(), "FastUuid.v4() must produce an IETF-variant UUID");
    }

    @Test
    void inboxIdNext_isV4_viaFastUuidSwap() {
        UUID u = InboxId.next().value();
        assertEquals(4, u.version());
        assertEquals(2, u.variant());
    }

    @Test
    void sagaIdRandom_isV4_viaFastUuidSwap() {
        UUID u = SagaId.random().value();
        assertEquals(4, u.version());
        assertEquals(2, u.variant());
    }

    @Test
    void scheduledCommandIdRandom_isV4_viaFastUuidSwap() {
        UUID u = ScheduledCommandId.random().value();
        assertEquals(4, u.version());
        assertEquals(2, u.variant());
    }

    static final class TickEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        TickEvent() {
            super("agg-1");
        }
    }

    @Test
    void abstractDomainEventId_isV4_viaFastUuidSwap() {
        UUID u = new TickEvent().getId();
        assertEquals(4, u.version());
        assertEquals(2, u.variant());
    }

    record TickBody() {
    }

    @Test
    void defaultCommandIdFallback_isV4_viaFastUuidSwap() {
        UUID u = Command.<TickBody>builder().body(new TickBody()).build().getCommandId();
        assertEquals(4, u.version());
        assertEquals(2, u.variant());
    }

    @Test
    void defaultQueryIdFallback_isV4_viaFastUuidSwap() {
        UUID u = Query.<TickBody>builder().body(new TickBody()).build().getQueryId();
        assertEquals(4, u.version());
        assertEquals(2, u.variant());
    }

    @Test
    void canonicalToStringFormat_isPreserved() {
        String s = MessageId.random().toString();
        // 8-4-4-4-12 hex digits with hyphens at fixed positions
        assertTrue(
                   s.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
                   "canonical UUID toString format must match 8-4-4-4-12 with v4 + IETF-variant nibbles; got: "
                           + s);
    }
}
