package net.nexus_flow.core.eventsourcing;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable snapshot of a {@link Projection}'s accumulated read-model state at a specific
 * {@code globalPosition}. Persisted through {@link ProjectionSnapshotStore} so a restarting
 * runner can rebuild the projection from the snapshot + the tail of new envelopes, instead
 * of replaying every envelope since global-position 0.
 *
 * @param projectionName the {@link Projection#name()} this snapshot belongs to; non-blank
 * @param globalPosition the {@code globalPosition} of the last envelope APPLIED before this
 *                       snapshot was taken; the runner resumes from {@code globalPosition + 1}
 * @param state          opaque state bytes — the projection's serialised read model. The
 *                       framework treats it as a blob; the projection decides the format via
 *                       {@link #stateType}
 * @param stateType      stable identifier of the {@code state} encoding (e.g.
 *                       {@code "json:v1"}, {@code "protobuf:OrderProjection.v2"}). Mirrors the
 *                       {@link net.nexus_flow.core.ddd.Aggregate.SnapshotState#stateType}
 *                       convention so projection authors can evolve the format
 */
public record ProjectionSnapshot(
                                 String projectionName,
                                 long globalPosition,
                                 byte[] state,
                                 String stateType) {

    public ProjectionSnapshot {
        Objects.requireNonNull(projectionName, "projectionName");
        if (projectionName.isBlank()) {
            throw new IllegalArgumentException("projectionName must not be blank");
        }
        if (globalPosition < 0) {
            throw new IllegalArgumentException(
                    "globalPosition must be >= 0: " + globalPosition);
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(stateType, "stateType");
        if (stateType.isBlank()) {
            throw new IllegalArgumentException("stateType must not be blank");
        }
        // Defensive copy: snapshots are immutable; protect against state-byte mutation by the
        // caller after construction.
        state = Arrays.copyOf(state, state.length);
    }

    @Override
    public byte[] state() {
        return Arrays.copyOf(state, state.length);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProjectionSnapshot(String name1, long position1, byte[] state1, String type1))) {
            return false;
        }
        return globalPosition == position1 && Objects.equals(projectionName, name1) && Arrays.equals(state, state1) && Objects.equals(
                                                                                                                                      stateType,
                                                                                                                                      type1);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(projectionName, globalPosition, stateType);
        result = 31 * result + Arrays.hashCode(state);
        return result;
    }

    @Override
    public String toString() {
        return "ProjectionSnapshot{projectionName='"
                + projectionName
                + "', globalPosition="
                + globalPosition
                + ", stateType='"
                + stateType
                + "', stateBytes="
                + state.length
                + '}';
    }
}
