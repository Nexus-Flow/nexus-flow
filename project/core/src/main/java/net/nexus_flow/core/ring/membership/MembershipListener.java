package net.nexus_flow.core.ring.membership;

/**
 * Subscribes to {@link MembershipEvent}s from a {@link MembershipRegistry}. Implementations
 * SHOULD be fast and non-blocking — the registry invokes them inline on the membership thread.
 * Long-running reactions (e.g. saga reassignment) MUST dispatch to a separate executor.
 */
@FunctionalInterface
public interface MembershipListener {

    /**
     * Invoked synchronously for every membership event in the order they happen. The listener
     * SHOULD NOT throw — exceptions propagate to the registry and may be logged at WARNING
     * but the registry does not unsubscribe the listener.
     *
     * @param event the event; never {@code null}
     */
    void onEvent(MembershipEvent event);
}
