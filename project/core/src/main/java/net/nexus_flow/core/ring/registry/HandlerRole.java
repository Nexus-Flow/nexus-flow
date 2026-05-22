package net.nexus_flow.core.ring.registry;

/**
 * Discriminator for handler-directory entries. Commands and queries are tracked separately
 * because a peer that handles {@code FooCommand} does not necessarily handle {@code FooQuery},
 * and the routing layer (R7) must not conflate them. Events use their own fan-out mechanism
 * (R5b RingEventBus) and are NOT tracked through this directory — every interested peer
 * subscribes locally.
 */
public enum HandlerRole {
    COMMAND,
    QUERY
}
