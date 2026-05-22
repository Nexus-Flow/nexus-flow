package net.nexus_flow.core.outbox;

/**
 * Shutdown semantics for {@link OutboxWorker#shutdown(ShutdownMode)} and {@link
 * net.nexus_flow.core.scheduling.ScheduledCommandWorker} (analogous overload).
 *
 * <p>The enum makes the trade-off explicit at the call site instead of inferring it from a {@code
 * drainOnShutdown} flag buried in the worker configuration. Callers that need both modes (e.g. a
 * process that does a graceful shutdown on SIGTERM but switches to immediate on a second SIGTERM)
 * get a single, readable API surface.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 * <li>{@link #GRACEFUL} — drain every eligible row through {@link OutboxWorker#drainOnce()} (up
 * to {@link OutboxConfig#drainShutdownCycles()} cycles) on the caller thread BEFORE
 * cancelling the worker token. Honour the at-least-once promise: deliver what's pending
 * before stopping. Suitable for normal app-level shutdown (SIGTERM, web-app undeploy, Spring
 * context close).
 * <li>{@link #IMMEDIATE} — skip the drain entirely. Cancel the worker token, interrupt the daemon
 * thread, join with {@link OutboxConfig#workerShutdownGrace()}. Pending rows stay PENDING and
 * will be re-claimed by another replica / on next startup. Suitable for emergency stops
 * (second SIGTERM, JVM hard-shutdown handlers, panic paths) where minimising shutdown latency
 * matters more than draining the queue.
 * </ul>
 *
 * <p>Cancellation remains cooperative regardless of mode — a handler that polls neither the worker
 * token nor {@code Thread.interrupted()} cannot be force-stopped. The configured shutdown grace
 * bounds the wait; the mode only decides whether to drain BEFORE that wait starts.
 *
 * <p>Backwards compatibility: {@link OutboxWorker#shutdown()} (no-arg) preserves the prior
 * behaviour — it inspects {@link OutboxConfig#drainOnShutdown()} and chooses {@link #GRACEFUL} or
 * {@link #IMMEDIATE} accordingly. Callers that want an explicit, config-independent decision use
 * the {@code shutdown(ShutdownMode)} overload.
 */
public enum ShutdownMode {

    /**
     * Drain eligible rows before cancelling the worker token. Honours the at-least-once delivery
     * promise; suitable for orderly shutdowns where the queue's drain latency is acceptable.
     */
    GRACEFUL,

    /**
     * Cancel immediately without draining. Suitable for emergency stops where minimising shutdown
     * latency outweighs draining the pending queue.
     */
    IMMEDIATE
}
