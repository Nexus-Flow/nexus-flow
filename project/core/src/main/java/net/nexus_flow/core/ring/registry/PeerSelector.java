package net.nexus_flow.core.ring.registry;

import java.util.Optional;
import java.util.Set;
import net.nexus_flow.core.ring.transport.PeerId;
import org.jspecify.annotations.Nullable;

/**
 * Strategy for picking one peer out of a candidate set returned by {@link
 * HandlerDirectory#whoHandles}. Lets routing policy (round-robin, hash-by-aggregate,
 * least-loaded, local-first, ...) be configured at runtime without changing the dispatch
 * code path.
 *
 * <p>Implementations MUST be thread-safe — the routing layer calls {@link #select} from any
 * thread that issues a cross-pod dispatch.
 */
@FunctionalInterface
public interface PeerSelector {

    /**
     * Pick one peer from {@code candidates}.
     *
     * @param candidates the set returned by {@link HandlerDirectory#whoHandles}; never {@code
     *     null}      , may be empty (selector returns {@link Optional#empty()})
     * @param routingKey optional routing key (typically aggregate id) — consulted by hash-ring
     *                   selectors for affinity. {@code null} means "no affinity hint"
     * @return the selected peer, or {@link Optional#empty()} if {@code candidates} is empty
     */
    Optional<PeerId> select(Set<PeerId> candidates, @Nullable String routingKey);
}
