package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.Serial;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * {@link EventBus} targeted unregister: unregistering a single listener instance must not drop
 * sibling listeners registered for the same event type.
 */
class EventBusTargetedUnregisterTest {

    static final class Pinged extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Pinged(String aggregateId) {
            super(aggregateId);
        }
    }

    /** Unregistering one listener preserves other listeners for the same event type. */
    @Test
    void unregister_oneListener_keepsSiblingsForSameEventType() {
        AtomicInteger firstCalls  = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();

        AbstractDomainEventListener<Pinged> first  =
                new AbstractDomainEventListener<>() {
                                                               @Override
                                                               public void handle(Pinged event) {
                                                                   firstCalls.incrementAndGet();
                                                               }
                                                           };
        AbstractDomainEventListener<Pinged> second =
                new AbstractDomainEventListener<>() {
                                                               @Override
                                                               public void handle(Pinged event) {
                                                                   secondCalls.incrementAndGet();
                                                               }
                                                           };

        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            runtime.events().register(first);
            runtime.events().register(second);

            runtime.events().unregister(first);
            runtime.events().dispatch(new Pinged("agg-unreg"), false);
        }

        assertEquals(0, firstCalls.get(), "unregistered listener must not be invoked");
        assertEquals(1, secondCalls.get(), "sibling listener must remain registered");
    }
}
