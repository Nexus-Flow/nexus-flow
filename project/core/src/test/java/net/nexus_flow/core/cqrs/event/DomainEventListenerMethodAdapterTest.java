package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.Serial;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.introspection.EventListenerRegistration;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

class DomainEventListenerMethodAdapterTest {

    static final class OrderPlaced extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;
        final String              orderId;

        OrderPlaced(String orderId) {
            super(orderId);
            this.orderId = orderId;
        }
    }

    static final class Bean {
        private final AtomicReference<String> seen = new AtomicReference<>();

        void on(OrderPlaced event) {
            seen.set(event.orderId);
        }
    }

    @Test
    void fromMethod_registersBeanMethodWithListenerOptions() throws NoSuchMethodException {
        Bean                      bean         = new Bean();
        Method                    method       = Bean.class.getDeclaredMethod("on", OrderPlaced.class);
        EventListenerRegistration registration =
                DomainEventListener.fromMethod(
                                               bean,
                                               method,
                                               new EventListenerOptions<>(-10, true, RetryPolicy.NO_RETRY, null, 1, false, null));

        assertEquals(OrderPlaced.class, registration.eventType().getType());
        try (FlowRuntime runtime = FlowRuntime.builder().parallelListeners(true).build()) {
            registration.registerOn(runtime.events());
            runtime.events().dispatchResult(new OrderPlaced("O-3"));
            assertEquals("O-3", bean.seen.get());
        }
    }
}
