package net.nexus_flow.core.eventsourcing;

import java.util.Arrays;
import java.util.Objects;

/**
 * Snapshot of an aggregate's state at a specific stream version.
 *
 * <p>{@link #state()} is opaque bytes whose interpretation is fixed by {@link #stateType()} (a
 * string the caller picks — typically the fully-qualified class name of the serialized form). The
 * in-memory store works with any encoding the user picks.
 *
 * <p>The compact constructor defensively copies the incoming {@code state} array so that the
 * snapshot is isolated from external mutations after construction.
 */
public record Snapshot(StreamId stream, long version, byte[] state, String stateType) {

    /**
     * Create an immutable snapshot value.
     *
     * @param stream    stream whose state was captured
     * @param version   committed stream version represented by {@code state}
     * @param state     opaque serialized snapshot bytes
     * @param stateType identifier of the {@code state} encoding
     * @throws NullPointerException     if {@code stream}, {@code state}, or {@code stateType} is {@code
     *     null}
     * @throws IllegalArgumentException if {@code version < 1} or {@code stateType} is blank
     */
    public Snapshot {
        Objects.requireNonNull(stream, "stream");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1: " + version);
        }
        Objects.requireNonNull(state, "state");
        state = state.clone(); // defensive copy — isolate stored array from caller mutations
        Objects.requireNonNull(stateType, "stateType");
        if (stateType.isBlank()) {
            throw new IllegalArgumentException("stateType must not be blank");
        }
    }

    /**
     * Return a defensive copy of the stored snapshot bytes.
     *
     * @return cloned snapshot payload
     */
    @Override
    public byte[] state() {
        return state.clone();
    }

    /**
     * Return the raw, NON-defensive view of the snapshot bytes. The returned array MUST be
     * treated as immutable by the caller — mutating it will corrupt the snapshot for every
     * subsequent reader.
     *
     * <p>Intended for hot replay / decode paths where the caller is the framework's own
     * deserializer and is known to not mutate the bytes. Adapters that need stronger isolation
     * use {@link #state()}.
     *
     * @return the internal byte array reference; never {@code null}
     */
    @SuppressWarnings("PMD.MethodReturnsInternalArray")
    public byte[] stateUnsafe() {
        return state;
    }

    /**
     * Compare snapshots by stream, version, payload bytes, and state type.
     *
     * @param o object to compare with this snapshot
     * @return {@code true} when the other object represents the same snapshot contents
     */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof Snapshot(StreamId stream1, long version1, byte[] state1, String type))) {
            return false;
        }
        return version == version1 && Objects.equals(stream, stream1) && Arrays.equals(state, state1) && Objects.equals(stateType, type);
    }

    /**
     * Compute a hash code consistent with {@link #equals(Object)}.
     *
     * @return snapshot hash code
     */
    @Override
    public int hashCode() {
        int result = Objects.hash(stream, version, stateType);
        result = 31 * result + Arrays.hashCode(state);
        return result;
    }

    /**
     * Render this snapshot for diagnostics.
     *
     * @return human-readable representation of the snapshot
     */
    @Override
    public String toString() {
        return "Snapshot{stream="
                + stream
                + ", version="
                + version
                + ", state="
                + Arrays.toString(state)
                + ", stateType='"
                + stateType
                + "'}";
    }
}
