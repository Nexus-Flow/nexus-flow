package net.nexus_flow.core.cqrs.command;

/**
 * per-handler back-pressure saturation policy.
 *
 * <p>Selects what happens when a dispatch arrives at a {@link DefaultCommandHandlerExecutor} whose
 * queue is already at {@link HandlerBackpressureSettings#queueDepth() queueDepth}:
 *
 * <ul>
 * <li>{@link #BLOCK_CALLER} — the caller blocks until a queue slot frees up (or until {@link
 * HandlerBackpressureSettings#blockTimeout() blockTimeout} elapses), with cooperative
 * cancellation checks honoring every poll. This is the documented default and preserves
 * behavior byte-for-byte when {@code queueDepth == Integer.MAX_VALUE}.
 * <li>{@link #DROP_NEWEST} — the just-arrived dispatch is rejected immediately; the executor
 * surfaces a {@link net.nexus_flow.core.runtime.result.DispatchResult.Failure} carrying a
 * {@link SaturationRejectedException}. Existing queued work proceeds undisturbed.
 * <li>{@link #DROP_OLDEST} — the head of the queue is evicted to make room for the new dispatch;
 * the evicted task is logged at {@code WARNING} level and never reaches the handler. Order of
 * the survivors is preserved.
 * <li>{@link #REJECT} — fast-fail: any dispatch that cannot be enqueued instantly surfaces a
 * {@link SaturationRejectedException} synchronously to the caller (no blocking, no eviction).
 * </ul>
 */
public enum SaturationPolicy {
    BLOCK_CALLER,
    DROP_NEWEST,
    DROP_OLDEST,
    REJECT
}
