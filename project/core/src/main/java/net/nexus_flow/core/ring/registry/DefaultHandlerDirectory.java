package net.nexus_flow.core.ring.registry;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.nexus_flow.core.ring.transport.PeerId;

/**
 * Concrete {@link HandlerDirectory} backed by a nested {@link ConcurrentHashMap}. Reads are
 * lock-free; writes are scoped to one role+type at a time via {@code compute*} primitives so
 * concurrent register/unregister from multiple membership integrations cannot lose entries.
 *
 * <h2>Empty-set hygiene</h2>
 *
 * When the last peer for a {@code (role, typeName)} unregisters, the entry is removed from
 * the map entirely (not left as an empty set). This keeps {@link #typesHandled(HandlerRole)}
 * accurate: a type with zero current handlers does NOT appear in the result.
 */
public final class DefaultHandlerDirectory implements HandlerDirectory {

    private final ConcurrentHashMap<HandlerRole, ConcurrentHashMap<String, Set<PeerId>>> byRole =
            new ConcurrentHashMap<>();

    public DefaultHandlerDirectory() {
        byRole.put(HandlerRole.COMMAND, new ConcurrentHashMap<>());
        byRole.put(HandlerRole.QUERY, new ConcurrentHashMap<>());
    }

    /**
     * Record that {@code peerId} handles every type in {@code typeNames} under {@code role}.
     * Replaces any prior advertisement for the same peer + role pair so the cluster view stays
     * consistent across peer redeployments that change the handler set.
     */
    public void register(HandlerRole role, PeerId peerId, Collection<String> typeNames) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(typeNames, "typeNames");
        unregister(role, peerId);
        ConcurrentHashMap<String, Set<PeerId>> roleMap = byRole.get(role);
        for (String typeName : typeNames) {
            Objects.requireNonNull(typeName, "typeName");
            roleMap.compute(
                            typeName,
                            (k, existing) -> {
                                if (existing == null) {
                                    return Set.of(peerId);
                                }
                                if (existing.contains(peerId)) {
                                    return existing;
                                }
                                var copy = new java.util.HashSet<>(existing);
                                copy.add(peerId);
                                return Set.copyOf(copy);
                            });
        }
    }

    /** Remove every advertisement {@code peerId} made under {@code role}. */
    public void unregister(HandlerRole role, PeerId peerId) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(peerId, "peerId");
        ConcurrentHashMap<String, Set<PeerId>> roleMap = byRole.get(role);
        roleMap.forEach(
                        (typeName, peers) -> {
                            if (peers.contains(peerId)) {
                                roleMap.compute(
                                                typeName,
                                                (k, existing) -> {
                                                    if (existing == null || !existing.contains(peerId)) {
                                                        return existing;
                                                    }
                                                    var copy = new java.util.HashSet<>(existing);
                                                    copy.remove(peerId);
                                                    return copy.isEmpty() ? null : Set.copyOf(copy);
                                                });
                            }
                        });
    }

    /** Convenience: remove every advertisement {@code peerId} made under EVERY role. */
    public void unregisterFromAllRoles(PeerId peerId) {
        for (HandlerRole role : HandlerRole.values()) {
            unregister(role, peerId);
        }
    }

    @Override
    public Set<PeerId> whoHandles(HandlerRole role, String typeName) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(typeName, "typeName");
        Set<PeerId> peers = byRole.get(role).get(typeName);
        return peers == null ? Set.of() : peers;
    }

    @Override
    public Set<String> typesHandled(HandlerRole role) {
        Objects.requireNonNull(role, "role");
        return Set.copyOf(byRole.get(role).keySet());
    }
}
