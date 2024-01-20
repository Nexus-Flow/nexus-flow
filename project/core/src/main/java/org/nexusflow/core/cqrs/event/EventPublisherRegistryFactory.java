package org.nexusflow.core.cqrs.event;

class EventPublisherRegistryFactory {
    private static volatile EventPublisherRegistry instance;

    private EventPublisherRegistryFactory() {
    }

    public static EventPublisherRegistry getInstance() {
        if (instance == null) {
            synchronized (EventPublisherRegistryFactory.class) {
                if (instance == null) {
                    instance = new DefaultEventPublisherRegistry();
                }
            }
        }
        return instance;
    }
}
