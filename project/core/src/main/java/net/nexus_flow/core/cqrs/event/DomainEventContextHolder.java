package net.nexus_flow.core.cqrs.event;

public class DomainEventContextHolder {
    private static final DomainEventContext INSTANCE;

    static {
        String contextType = System.getProperty("eventContextStrategy", "scoped");
        if ("thread-local".equalsIgnoreCase(contextType)) {
            INSTANCE = new ThreadLocalDomainEventContext();
        } else {
            INSTANCE = new ScopedDomainEventContext();
        }
    }

    public static DomainEventContext getContext() {
        return INSTANCE;
    }
}
