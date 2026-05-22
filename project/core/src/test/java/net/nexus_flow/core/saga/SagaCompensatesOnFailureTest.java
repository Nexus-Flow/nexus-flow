package net.nexus_flow.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.time.Clock;
import java.util.*;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.eventsourcing.EventEnvelope;
import net.nexus_flow.core.eventsourcing.EventStore;
import net.nexus_flow.core.eventsourcing.InMemoryEventStore;
import net.nexus_flow.core.eventsourcing.StreamId;
import net.nexus_flow.core.outbox.*;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * Verifies a {@link Saga} reacts to a domain failure event by emitting a compensation event that
 * lands in the outbox, guaranteeing durable delivery.
 */
class SagaCompensatesOnFailureTest {

    // ---- Saga-driven domain events
    static final class OrderPlaced extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        OrderPlaced(String aggId) {
            super(aggId);
        }
    }

    static final class PaymentFailed extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        PaymentFailed(String aggId) {
            super(aggId);
        }
    }

    static final class CancelOrder extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        CancelOrder(String aggId) {
            super(aggId);
        }
    }

    /**
     * Aggregate used both for the original events and for stamping sequence numbers on the
     * compensation event (required by the OutboxAppender contract).
     */
    static final class Order extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;
        private final UUID        id;

        Order(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getAggregateId() {
            return id;
        }

        void place() {
            recordEvent(new OrderPlaced(id.toString()));
        }

        void paymentFails() {
            recordEvent(new PaymentFailed(id.toString()));
        }

        DomainEvent recordCancel() {
            CancelOrder ev = new CancelOrder(id.toString());
            recordEvent(ev);
            return ev;
        }
    }

    /** Saga: on PaymentFailed → emit CancelOrder compensation. */
    static final class OrderSaga implements Saga {
        final Order compensationStamper;

        OrderSaga(Order compensationStamper) {
            this.compensationStamper = compensationStamper;
        }

        @Override
        public String type() {
            return "order-saga";
        }

        @Override
        public Optional<String> correlationKeyFor(EventEnvelope env) {
            DomainEvent e = env.payload();
            if (e instanceof OrderPlaced || e instanceof PaymentFailed) {
                return Optional.of(e.getAggregateId());
            }
            return Optional.empty();
        }

        @Override
        public SagaTransition handle(EventEnvelope env, SagaState state) {
            if (env.payload() instanceof OrderPlaced) {
                // Track that the order is awaiting payment.
                Map<String, Object> data = new HashMap<>(state.data());
                data.put("status", "awaiting-payment");
                return new SagaTransition.Continue(data);
            }
            if (env.payload() instanceof PaymentFailed) {
                Map<String, Object> data = new HashMap<>(state.data());
                data.put("status", "cancelled");
                DomainEvent cancel = compensationStamper.recordCancel();
                return new SagaTransition.Compensate(data, List.of(cancel));
            }
            return new SagaTransition.Continue(state.data());
        }
    }

    @Test
    void paymentFailed_makesCompensation_throughOutbox() {
        Clock                 clock       = Clock.systemUTC();
        EventStore            eventStore  = new InMemoryEventStore(clock);
        SagaStorage           sagaStorage = new InMemorySagaStorage();
        InMemoryOutboxStorage outbox      = new InMemoryOutboxStorage(clock);
        OutboxPayloadCodec    codec       = new JavaSerializationOutboxPayloadCodec();

        // Seed the order's event stream.
        UUID  orderId = UUID.randomUUID();
        Order order   = new Order(orderId);
        order.place();
        order.paymentFails();
        // Persist the two original events to the event store.
        StreamId stream = StreamId.of(Order.class, orderId);
        eventStore.append(stream, 0L, order.drainEvents());

        // Wire the saga with a separate Order aggregate solely for
        // stamping the compensation event's sequence number.
        Order      compensationStamper = new Order(orderId);
        OrderSaga  saga                = new OrderSaga(compensationStamper);
        SagaRunner runner              = new SagaRunner(eventStore, sagaStorage, saga, outbox, codec, clock);

        long handled = runner.catchUp(ExecutionContext.root());
        assertEquals(2L, handled, "saga must observe OrderPlaced and PaymentFailed");

        // The compensation event landed in the outbox as PENDING.
        List<OutboxRecord> rows = outbox.snapshot();
        assertEquals(1, rows.size(), "exactly one compensation row");
        OutboxRecord row = rows.getFirst();
        assertEquals(OutboxStatus.PENDING, row.status());
        assertEquals(CancelOrder.class, row.payloadType(), "compensation event must be CancelOrder");

        // The saga is now COMPENSATED.
        SagaState finalState = sagaStorage.load("order-saga", orderId.toString()).orElseThrow();
        assertEquals(SagaStatus.COMPENSATED, finalState.status());
        assertEquals("cancelled", finalState.data().get("status"));
        assertTrue(
                   finalState.lastProcessedGlobalPosition() >= 2,
                   "saga checkpoint must advance past every consumed envelope");
    }
}
