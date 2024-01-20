package net.nexus_flow.core.cqrs.event;

import net.nexus_flow.core.ddd.DomainEvent;

public sealed interface EventBus permits DefaultEventBus {
    static EventBus getInstance() {
        return EventBusFactory.getInstance();
    }

    <E extends DomainEvent> void register(DomainEventListener<E> listener);

    <E extends DomainEvent> void unregister(DomainEventListener<E> listener);

    <E extends DomainEvent> void dispatch(E event, boolean isSaga);

    class EventBusFactory {
        private static volatile EventBus instance;

        private EventBusFactory() {
        }

        public static EventBus getInstance() {
            if (instance == null) {
                synchronized (EventBusFactory.class) {
                    if (instance == null) {
                        instance = new DefaultEventBus();
                    }
                }
            }
            return instance;
        }
    }
}