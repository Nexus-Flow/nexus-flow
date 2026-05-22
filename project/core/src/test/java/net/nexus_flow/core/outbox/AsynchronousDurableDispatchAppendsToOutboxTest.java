package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.junit.jupiter.api.Test;

/**
 * durable async dispatch appends to the outbox and returns {@link DispatchResult.Accepted} carrying
 * the {@code messageId}.
 */
class AsynchronousDurableDispatchAppendsToOutboxTest {

    static final class Pong extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Pong(String aggId) {
            super(aggId);
        }
    }

    /** Minimal aggregate used solely to stampsequence numbers. */
    static final class Sender extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;
        private final UUID        id;

        Sender(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getAggregateId() {
            return id;
        }

        DomainEvent emitPong() {
            Pong p = new Pong(id.toString());
            recordEvent(p);
            return p;
        }
    }

    @Test
    void acceptAndAppend_appendsExactlyOnePendingRow_andReturnsAccepted() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        Clock                 clock   = Clock.systemUTC();
        ExecutionContext      ctx     = ExecutionContext.root();

        UUID        aggId  = UUID.randomUUID();
        Sender      sender = new Sender(aggId);
        DomainEvent event  = sender.emitPong();

        DispatchResult<Void> result = DurableDispatch.acceptAndAppend(event, ctx, storage, clock);

        @SuppressWarnings("unchecked") DispatchResult.Accepted<Void> accepted =
                assertInstanceOf(DispatchResult.Accepted.class, result);
        assertNotNull(accepted.messageId());
        assertEquals(
                     ctx.messageId(), accepted.messageId(), "Accepted must carry the dispatch's messageId");

        List<OutboxRecord> rows = storage.snapshot();
        assertEquals(1, rows.size());
        OutboxRecord r = rows.getFirst();
        assertEquals(OutboxStatus.PENDING, r.status());
        assertEquals(ctx.messageId(), r.messageId());
        assertEquals(ctx.traceId(), r.traceId());
        assertEquals(ctx.correlationId(), r.correlationId());
        assertEquals(Pong.class.getName(), r.aggregateType());
        assertTrue(
                   r.payloadBytes().length == 0,
                   "codec-less overload leaves payloadBytes empty (V2.8 contract)");
    }

    @Test
    void acceptAndAppend_codecVariant_encodesPayloadBytes() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        Clock                 clock   = Clock.systemUTC();
        ExecutionContext      ctx     = ExecutionContext.root();

        UUID        aggId  = UUID.randomUUID();
        Sender      sender = new Sender(aggId);
        DomainEvent event  = sender.emitPong();

        OutboxPayloadCodec   codec  = new JavaSerializationOutboxPayloadCodec();
        DispatchResult<Void> result =
                DurableDispatch.acceptAndAppend(event, ctx, storage, clock, codec);
        assertInstanceOf(DispatchResult.Accepted.class, result);

        OutboxRecord r = storage.snapshot().getFirst();
        assertTrue(r.payloadBytes().length > 0, "codec variant must populate payloadBytes");
    }
}
