package net.nexus_flow.core.eventsourcing;

/**
 * Sealed outcome of {@link EventStore#append(StreamId, long, java.util.List)}.
 *
 * <ul>
 * <li>{@link Success} — the append committed; carries the new committed {@code streamVersion}
 * after the batch and the {@code globalPosition} of the FIRST envelope of the batch
 * (subsequent envelopes are at {@code firstGlobalPosition + i}).
 * <li>{@link VersionConflict} — the optimistic-concurrency check failed; carries the expected and
 * actual stream versions for diagnostics. The caller MUST NOT retry the append blindly — it
 * has to re-read the stream and rebase its aggregate state.
 * </ul>
 */
public sealed interface AppendResult {

    /**
     * Successful append.
     *
     * @param newVersion          new committed stream version (1-based, >= 1)
     * @param firstGlobalPosition global position of the first envelope in the batch
     */
    record Success(long newVersion, long firstGlobalPosition) implements AppendResult {
        /**
         * Create a successful append result.
         *
         * @param newVersion          new committed stream version after the batch
         * @param firstGlobalPosition global position of the batch's first envelope
         * @throws IllegalArgumentException if either value is less than {@code 1}
         */
        public Success {
            if (newVersion < 1) {
                throw new IllegalArgumentException("newVersion must be >= 1: " + newVersion);
            }
            if (firstGlobalPosition < 1) {
                throw new IllegalArgumentException(
                        "firstGlobalPosition must be >= 1: " + firstGlobalPosition);
            }
        }
    }

    /**
     * Optimistic-concurrency failure; the caller should re-load, rebase, and retry.
     *
     * @param expectedVersion the version the caller assumed
     * @param actualVersion   the version actually found in the store
     */
    record VersionConflict(long expectedVersion, long actualVersion) implements AppendResult {
        /**
         * Create a version-conflict result.
         *
         * @param expectedVersion version assumed by the caller
         * @param actualVersion   version actually present in the store
         * @throws IllegalArgumentException if {@code expectedVersion < -1} or {@code actualVersion < 0}
         */
        public VersionConflict {
            if (expectedVersion < -1) {
                throw new IllegalArgumentException("expectedVersion must be >= -1: " + expectedVersion);
            }
            if (actualVersion < 0) {
                throw new IllegalArgumentException("actualVersion must be >= 0: " + actualVersion);
            }
        }
    }
}
