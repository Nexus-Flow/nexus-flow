package net.nexus_flow.core.cqrs.query;

class QueryConsumerRegistryFactory {
    private static volatile QueryConsumerRegistry instance;

    private QueryConsumerRegistryFactory() {
    }

    public static QueryConsumerRegistry getInstance() {
        if (instance == null) {
            synchronized (QueryConsumerRegistry.class) {
                if (instance == null) {
                    instance = new DefaultQueryConsumerRegistry();
                }
            }
        }
        return instance;
    }
}
