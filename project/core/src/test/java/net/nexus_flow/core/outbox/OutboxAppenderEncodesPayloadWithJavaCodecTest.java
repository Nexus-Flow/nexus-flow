package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * commit (a) — the codec-aware overload of {@link OutboxAppender#appendDrainedEvents(List,
 * ExecutionContext, OutboxStorage, Clock, OutboxPayloadCodec)} persists a non-empty {@code
 * payloadBytes}, and a round-trip through {@link JavaSerializationOutboxPayloadCodec#decode(byte[],
 * Class)} yields an event with the sameidempotency key.
 *
 * <p>The legacy 4-arg overload still produces an empty payload — that invariant is pinned by's
 * {@code OutboxAppendIdempotencyKeyPersistedTest} which we intentionally do not change.
 */
class OutboxAppenderEncodesPayloadWithJavaCodecTest {

    static final class Bumped extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;
        private final int         amount;

        Bumped(String aggregateId, int amount) {
            super(aggregateId);
            this.amount = amount;
        }

        int amount() {
            return amount;
        }
    }

    static final class Counter extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void bump(int by) {
            recordEvent(new Bumped("ctr-1", by));
        }
    }

    @Test
    void codecAwareOverload_writesNonEmptyPayload_andRoundTripsToSameIdempotencyKey() {
        Counter c = new Counter();
        c.bump(7);
        c.bump(11);
        List<DomainEvent> drained = c.drainEvents();
        assertEquals(2, drained.size());

        Instant               fixed   = Instant.parse("2026-05-19T10:00:00Z");
        Clock                 clock   = Clock.fixed(fixed, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec   = new JavaSerializationOutboxPayloadCodec();

        // WHEN: codec-aware append
        OutboxAppender.appendDrainedEvents(drained, ExecutionContext.root(), storage, clock, codec);

        // THEN: every row has a non-empty payload
        List<OutboxRecord> rows =
                storage.snapshot().stream()
                        .sorted(Comparator.comparingLong(OutboxRecord::sequenceNo))
                        .toList();
        assertEquals(2, rows.size());
        for (OutboxRecord r : rows) {
            assertNotNull(r.payloadBytes());
            assertTrue(
                       r.payloadBytes().length > 0,
                       "codec-aware path must produce a non-empty payloadBytes; row=" + r);
        }
        // And the two payloads must differ (different amount values).
        assertNotEquals(0, rows.getFirst().payloadBytes().length, "first row payload bytes");

        // AND: each row round-trips to an event whose idempotencyKey
        // matches the persisted IdempotencyKey (contract).
        for (OutboxRecord r : rows) {
            DomainEvent decoded = codec.decode(r.payloadBytes(), r.payloadType());
            assertEquals(
                         r.idempotencyKey().value(),
                         decoded.idempotencyKey(),
                         "round-trip must preserveidempotencyKey for " + r);
        }
        // AND: the decoded payload carries the original aggregate state.
        Bumped first  = (Bumped) codec.decode(rows.get(0).payloadBytes(), rows.get(0).payloadType());
        Bumped second = (Bumped) codec.decode(rows.get(1).payloadBytes(), rows.get(1).payloadType());
        assertEquals(7, first.amount());
        assertEquals(11, second.amount());
    }

    @Test
    void legacyFourArgOverload_keepsEmptyPayload() {
        Counter c = new Counter();
        c.bump(1);
        List<DomainEvent> drained = c.drainEvents();

        Clock                 clock   = Clock.fixed(Instant.parse("2026-05-19T10:00:00Z"), ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        OutboxAppender.appendDrainedEvents(drained, ExecutionContext.root(), storage, clock);

        OutboxRecord row = storage.snapshot().getFirst();
        assertEquals(
                     0,
                     row.payloadBytes().length,
                     "legacy 4-arg overload must still produce empty payloadBytes");
    }
}
