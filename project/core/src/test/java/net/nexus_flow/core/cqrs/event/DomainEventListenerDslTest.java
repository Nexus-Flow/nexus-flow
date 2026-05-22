package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Validates the ergonomic {@code DomainEventListener.forEvent(Class)} DSL. */
class DomainEventListenerDslTest {

    static final class OrderShipped extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;
        final String              orderId;

        OrderShipped(String orderId) {
            super(orderId);
            this.orderId = orderId;
        }
    }

    static final class PaymentReceived extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;
        final String              paymentId;

        PaymentReceived(String paymentId) {
            super(paymentId);
            this.paymentId = paymentId;
        }
    }

    @Nested
    @DisplayName("forEvent(Class) — basic flow")
    class BasicFlow {

        @Test
        void handle_buildsTypedListener_thatRoutesEndToEnd() {
            try (FlowRuntime runtime = FlowRuntime.builder().build()) {
                AtomicReference<String> captured = new AtomicReference<>();

                var listener =
                        DomainEventListener.forEvent(OrderShipped.class).handle(e -> captured.set(e.orderId));

                assertInstanceOf(
                                 AbstractDomainEventListener.class,
                                 listener,
                                 "DSL-produced listener must be an AbstractDomainEventListener");
                assertEquals(
                             OrderShipped.class,
                             ((AbstractDomainEventListener<?>) listener).getEventType().getType(),
                             "EventType routing key must be OrderShipped");

                runtime.events().register(listener);
                try {
                    runtime.events().dispatchResult(new OrderShipped("O-42"));
                    assertEquals("O-42", captured.get());
                } finally {
                    runtime.events().unregister(listener);
                }
            }
        }

        @Test
        void dsl_produces_independentListeners_perEventType() {
            var shipListener = DomainEventListener.forEvent(OrderShipped.class).handle(_ -> {
                             });
            var payListener  = DomainEventListener.forEvent(PaymentReceived.class).handle(_ -> {
                             });

            assertEquals(
                         OrderShipped.class,
                         ((AbstractDomainEventListener<?>) shipListener).getEventType().getType());
            assertEquals(
                         PaymentReceived.class,
                         ((AbstractDomainEventListener<?>) payListener).getEventType().getType());
        }
    }

    @Nested
    @DisplayName("Null guards")
    class NullGuards {

        @Test
        void forEvent_rejectsNullClass() {
            assertThrows(NullPointerException.class, () -> DomainEventListener.forEvent(null));
        }

        @Test
        void handle_rejectsNullConsumer() {
            assertThrows(
                         NullPointerException.class,
                         () -> DomainEventListener.forEvent(OrderShipped.class).handle(null));
        }
    }

    @Nested
    @DisplayName("Sealed DSL contract")
    class SealedSurface {

        @Test
        void eventStep_isSealed() {
            assertTrue(DomainEventListenerDsl.EventStep.class.isSealed(), "EventStep must be sealed");
        }

        @Test
        void dsl_isNotInstantiable() {
            assertThrows(
                         Exception.class,
                         () -> {
                             var ctor = DomainEventListenerDsl.class.getDeclaredConstructor();
                             ctor.setAccessible(true);
                             ctor.newInstance();
                         });
        }
    }
}
