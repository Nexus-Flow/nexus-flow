package net.nexus_flow.core.ring.event;

/**
 * Declares which subsystem owns the local outbox's drain rights. The framework's
 * {@link net.nexus_flow.core.outbox.OutboxWorker} and the ring's {@link RingOutboxBridge}
 * both consume from the same {@link net.nexus_flow.core.outbox.OutboxStorage}. Without an
 * explicit ownership declaration, an operator who wires both into the same {@code
 * FlowRuntime} silently double-publishes every event.
 *
 * <h2>Decision matrix</h2>
 *
 * <ul>
 * <li>{@link #LOCAL_WORKER_ONLY} — single-pod deployments OR deployments where the local
 * worker handles delivery and the ring is used purely for cross-pod control plane
 * (heartbeats, sagas, queries). The bridge refuses to start; the worker drains the
 * outbox via {@code claimBatch}.
 * <li>{@link #RING_BRIDGE_ONLY} — multi-pod deployments where the ring is the primary
 * fan-out path. The bridge drains the outbox (via {@code claimBatch} for the
 * authoritative path and {@code findSinceSequence} for non-destructive replay); the
 * worker MUST NOT be wired into the same runtime.
 * <li>{@link #RING_BRIDGE_WITH_WORKER_FAILOVER} — bridge is primary, worker is the
 * fallback that takes over when the bridge stops. The handshake is now formal:
 * {@link RingOutboxBridge#attachOutboxWorker(net.nexus_flow.core.outbox.OutboxWorker)}
 * registers the worker; {@code start()} pauses it; {@code close()} resumes it. While the
 * bridge is alive it is the sole publisher (the worker is in {@link
 * net.nexus_flow.core.outbox.OutboxWorker#pause() pause}d state). The moment the bridge
 * closes — clean shutdown, scheduler crash, or {@link AutoCloseable#close()} from a
 * try-with-resources — the worker resumes and drains any rows left PENDING. At-least-once
 * is preserved across the bridge's full lifetime.
 * </ul>
 *
 * <h2>Why an enum and not just docs</h2>
 *
 * The original RING.md warned that "production wiring chooses ONE — mixing them
 * double-publishes locally". That warning was the contract; nothing enforced it. The bridge
 * now refuses to start when {@code ownership != RING_BRIDGE_ONLY} so the misconfiguration
 * fails fast at startup instead of producing duplicate events under load.
 */
public enum OutboxOwnership {

    /** Local worker drains the outbox; ring bridge MUST NOT be started. */
    LOCAL_WORKER_ONLY,

    /** Ring bridge drains the outbox; local worker MUST NOT be wired into the same runtime. */
    RING_BRIDGE_ONLY,

    /**
     * Bridge is the primary publisher; an attached {@link
     * net.nexus_flow.core.outbox.OutboxWorker} is paused while the bridge is alive and
     * resumed when the bridge closes. Register the worker via
     * {@link RingOutboxBridge#attachOutboxWorker(net.nexus_flow.core.outbox.OutboxWorker)}
     * before calling {@link RingOutboxBridge#start()}. If no worker is attached, the bridge
     * logs a WARNING at start and behaves like {@link #RING_BRIDGE_ONLY} for that run.
     */
    RING_BRIDGE_WITH_WORKER_FAILOVER
}
