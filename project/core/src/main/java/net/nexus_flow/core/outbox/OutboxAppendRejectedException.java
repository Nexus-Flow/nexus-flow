package net.nexus_flow.core.outbox;

import java.io.Serial;

/**
 * Thrown by the runtime when an outbox append is rejected because
 * {@link OutboxStorage#pendingCount()} has crossed the configured
 * {@link OutboxAppendBackpressureSettings#maxPendingRows()} threshold AND the policy is
 * {@link OutboxAppendBackpressureSettings.Policy#REJECT}. The events were NOT written to the
 * outbox; the calling command handler's transaction (when present) should roll back so the
 * client can retry.
 */
public final class OutboxAppendRejectedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long pendingCount;
    private final long maxPendingRows;

    /**
     * @param pendingCount   the observed pending-row count at the moment of rejection
     * @param maxPendingRows the configured ceiling that was crossed
     */
    public OutboxAppendRejectedException(long pendingCount, long maxPendingRows) {
        // Stack-traceless — the actionable info is pendingCount + maxPendingRows + the
        // configured policy. Stack trace at rejection points to the outbox-write site, not the
        // overload origin. Saves ~200 ns per rejection on the saturated-producer hot path.
        super(
              "outbox append rejected by backpressure policy — pendingCount="
                      + pendingCount
                      + " >= maxPendingRows="
                      + maxPendingRows
                      + ". The OutboxWorker is slower than the producer; either raise the"
                      + " threshold, scale the worker, or accept the rejection at the caller.",
              /* cause= */ null,
              // Suppression chain remains active — only the stack trace is skipped.
              /* enableSuppression= */ true,
              /* writableStackTrace= */ false);
        this.pendingCount   = pendingCount;
        this.maxPendingRows = maxPendingRows;
    }

    /** @return the observed pending-row count at the moment of rejection. */
    public long pendingCount() {
        return pendingCount;
    }

    /** @return the configured ceiling that was crossed. */
    public long maxPendingRows() {
        return maxPendingRows;
    }
}
