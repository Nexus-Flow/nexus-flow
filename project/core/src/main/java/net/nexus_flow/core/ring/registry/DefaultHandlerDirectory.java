package net.nexus_flow.core.ring.registry;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
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
 *
 * <h2>Reverse index</h2>
 *
 * A secondary {@code typesByPeerIndex} tracks the {@code (role, typeName)} pairs each peer
 * has advertised. {@link #unregister(HandlerRole, PeerId)} consults this index and touches
 * ONLY the affected type entries — O(K-for-peer) instead of the previous O(T-total) scan
 * over every advertised type.
 */
public final class DefaultHandlerDirectory implements HandlerDirectory {

    private final ConcurrentHashMap<HandlerRole, ConcurrentHashMap<String, Set<PeerId>>> peersByRoleAndTypeIndex =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PeerId, Map<HandlerRole, Set<String>>>               typesByPeerIndex        =
            new ConcurrentHashMap<>();

    public DefaultHandlerDirectory() {
        peersByRoleAndTypeIndex.put(HandlerRole.COMMAND, new ConcurrentHashMap<>());
        peersByRoleAndTypeIndex.put(HandlerRole.QUERY, new ConcurrentHashMap<>());
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
        ConcurrentHashMap<String, Set<PeerId>> roleMap = peersByRoleAndTypeIndex.get(role);
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
        typesByPeerIndex.compute(
                                 peerId,
                                 (k, existing) -> {
                                     Map<HandlerRole, Set<String>> next = existing == null ? new EnumMap<>(
                                             HandlerRole.class) : new EnumMap<>(existing);
                                     next.put(role, Set.copyOf(typeNames));
                                     return Map.copyOf(next);
                                 });
    }

    /** Remove every advertisement {@code peerId} made under {@code role}. */
    public void unregister(HandlerRole role, PeerId peerId) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(peerId, "peerId");
        ConcurrentHashMap<String, Set<PeerId>> roleMap = peersByRoleAndTypeIndex.get(role);
        Map<HandlerRole, Set<String>>          peerMap = typesByPeerIndex.get(peerId);
        if (peerMap == null) {
            return;
        }
        Set<String> typesForRole = peerMap.get(role);
        if (typesForRole == null || typesForRole.isEmpty()) {
            return;
        }
        for (String typeName : typesForRole) {
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
        typesByPeerIndex.compute(
                                 peerId,
                                 (k, existing) -> {
                                     if (existing == null) {
                                         return null;
                                     }
                                     Map<HandlerRole, Set<String>> next = new EnumMap<>(existing);
                                     next.remove(role);
                                     return next.isEmpty() ? null : Map.copyOf(next);
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
        Set<PeerId> peers = peersByRoleAndTypeIndex.get(role).get(typeName);
        return peers == null ? Set.of() : peers;
    }

    @Override
    public Set<String> typesHandled(HandlerRole role) {
        Objects.requireNonNull(role, "role");
        return Set.copyOf(peersByRoleAndTypeIndex.get(role).keySet());
    }
}
