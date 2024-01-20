package org.nexusflow.core.cqrs.command;

class CommandConsumerRegistryFactory {
    private static volatile CommandConsumerRegistry instance;

    private CommandConsumerRegistryFactory() {
    }

    public static CommandConsumerRegistry getInstance() {
        if (instance == null) {
            synchronized (CommandConsumerRegistry.class) {
                if (instance == null) {
                    instance = new DefaultCommandConsumerRegistry();
                }
            }
        }
        return instance;
    }
}
