package net.nexus_flow.core.ring.registry;

import java.util.Set;
import net.nexus_flow.core.ring.transport.PeerId;

/**
 * Read API for the distributed handler-location directory. Higher layers (R7's RingCommandBus
 * / RingQueryBus) consult this to find candidate peers for a given type. Implementations MUST
 * be safe for concurrent reads from any thread.
 */
public interface HandlerDirectory {

    /**
     * @param role     command or query
     * @param typeName fully-qualified type name to look up
     * @return immutable snapshot of the peers currently advertising handling of {@code
     *     (role, typeName)}; never {@code null}, may be empty
     */
    Set<PeerId> whoHandles(HandlerRole role, String typeName);

    /** Returns the set of every type name currently advertised by any peer for {@code role}. */
    Set<String> typesHandled(HandlerRole role);
}
