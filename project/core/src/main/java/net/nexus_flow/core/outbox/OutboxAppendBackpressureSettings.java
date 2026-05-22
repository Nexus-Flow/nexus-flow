package net.nexus_flow.core.outbox;

import java.util.Objects;

/**
 * Settings that govern append-side backpressure on the outbox. Wired through {@link
 * OutboxConfig#appendBackpressure()}, consulted by the runtime before draining a handler's
 * events to the outbox: when {@link OutboxStorage#pendingCount()} crosses {@link
 * #maxPendingRows()}, the configured {@link Policy} decides whether to reject, drop, or block
 * the upcoming append.
 *
 * <p>Without these settings the outbox grows unboundedly when the worker is slower than the
 * producers — eventually exhausting the storage budget (disk space on JDBC, RAM on the
 * in-memory test backend). The default {@link #UNLIMITED} setting preserves the historical
 * behaviour (no append cap) so existing deployments are unaffected.
 *
 * <h2>Interaction with {@link OutboxStorage#pendingCount()}</h2>
 *
 * Backpressure is silently skipped when the storage backend returns {@code -1L} (unknown) from
 * {@link OutboxStorage#pendingCount()} — the runtime cannot decide saturation without a count.
 * Operators wanting backpressure MUST wire a storage backend that overrides
 * {@link OutboxStorage#pendingCount()}; the in-memory backend does, JDBC adapter modules
 * normally do, exotic backends MAY not.
 *
 * @param maxPendingRows maximum number of {@link OutboxStatus#PENDING} rows tolerated before
 *                       {@link Policy} fires; must be {@code >= 1} when {@code policy} is not
 *                       {@link Policy#UNLIMITED}
 * @param policy         saturation policy; see {@link Policy}
 */
public record OutboxAppendBackpressureSettings(long maxPendingRows, Policy policy) {

    /** Default — append always accepted regardless of pending count (historical behaviour). */
    public static final OutboxAppendBackpressureSettings UNLIMITED =
            new OutboxAppendBackpressureSettings(Long.MAX_VALUE, Policy.UNLIMITED);

    /**
     * What to do when the pending-row count crosses {@link #maxPendingRows()}.
     */
    public enum Policy {
        /** No cap; pending row count is ignored. */
        UNLIMITED,
        /**
         * Reject the append: throw {@link OutboxAppendRejectedException}. The command handler
         * sees the exception as the dispatch failure and the caller can retry later. Events
         * are NOT appended; aggregate state changes already applied to in-memory objects are
         * the caller's responsibility (typical pattern: handler runs in a transaction that
         * rolls back on the throw).
         */
        REJECT,
        /**
         * Silently skip the append. The events are dropped at the outbox boundary — useful
         * for low-priority informational events where occasional loss is preferable to
         * blocking the producer. Inline listener dispatch (when {@link
         * OutboxConfig#useOutboxFanOut()} is false) still fires; only the durable write is
         * skipped.
         */
        DROP
    }

    public OutboxAppendBackpressureSettings {
        Objects.requireNonNull(policy, "policy");
        if (policy != Policy.UNLIMITED && maxPendingRows < 1) {
            throw new IllegalArgumentException(
                    "maxPendingRows must be >= 1 when policy != UNLIMITED: " + maxPendingRows);
        }
    }

    /**
     * Convenience factory for the {@link Policy#REJECT} shape with a single cap.
     *
     * @param maxPendingRows pending-row cap; must be {@code >= 1}
     */
    public static OutboxAppendBackpressureSettings reject(long maxPendingRows) {
        return new OutboxAppendBackpressureSettings(maxPendingRows, Policy.REJECT);
    }

    /**
     * Convenience factory for the {@link Policy#DROP} shape with a single cap.
     *
     * @param maxPendingRows pending-row cap; must be {@code >= 1}
     */
    public static OutboxAppendBackpressureSettings drop(long maxPendingRows) {
        return new OutboxAppendBackpressureSettings(maxPendingRows, Policy.DROP);
    }
}
