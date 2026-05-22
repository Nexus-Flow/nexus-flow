package net.nexus_flow.core.ring.dispatch;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.nexus_flow.core.ring.observability.RingJfr;
import net.nexus_flow.core.ring.observability.RingMetrics;
import net.nexus_flow.core.ring.registry.HandlerDirectory;
import net.nexus_flow.core.ring.registry.HandlerRole;
import net.nexus_flow.core.ring.registry.PeerSelector;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingConnection;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import net.nexus_flow.core.ring.wire.FrameType;
import net.nexus_flow.core.ring.wire.ProtocolErrorCode;
import net.nexus_flow.core.ring.wire.RingFrame;
import org.jspecify.annotations.Nullable;

/**
 * Cross-pod command/query routing client.
 *
 * <h2>Cancellation on peer disconnect (audit finding #4)</h2>
 *
 * The dispatcher hooks {@link RingConnection#close(Throwable)} via the
 * {@link net.nexus_flow.core.ring.transport.RingFrameHandler#onClosed(RingConnection, Throwable)}
 * callback: when a peer disconnects, every pending
 * dispatch bound to that peer is cancelled exceptionally with the close cause — no more
 * waiting the full timeout for a connection that's already gone. This is wired by
 * {@link #onConnectionClosed(PeerId, Throwable)}, which the application calls from its
 * {@code RingFrameHandler.onClosed} or the {@link
 * net.nexus_flow.core.ring.transport.RingConnectionRegistry} eviction path.
 *
 * <h2>Deadline + observability</h2>
 *
 * Each dispatch records its start nanos, attaches the response handler that completes the
 * latency timer with the {@link ProtocolErrorCode} of the outcome, and emits a {@link
 * RingJfr.Dispatch} JFR event on completion.
 */
public final class RingDispatcher {

    private final PeerId                  localPeerId;
    private final RingConnectionRegistry  connections;
    private final HandlerDirectory        directory;
    private final PeerSelector            selector;
    private final PendingResponseRegistry pending;
    private final Clock                   clock;
    private final RingMetrics             metrics;

    public RingDispatcher(
            PeerId localPeerId,
            RingConnectionRegistry connections,
            HandlerDirectory directory,
            PeerSelector selector,
            PendingResponseRegistry pending) {
        this(localPeerId,
             connections,
             directory,
             selector,
             pending,
             Clock.systemUTC(),
             RingMetrics.noOp());
    }

    public RingDispatcher(
            PeerId localPeerId,
            RingConnectionRegistry connections,
            HandlerDirectory directory,
            PeerSelector selector,
            PendingResponseRegistry pending,
            Clock clock,
            RingMetrics metrics) {
        this.localPeerId = Objects.requireNonNull(localPeerId, "localPeerId");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.directory   = Objects.requireNonNull(directory, "directory");
        this.selector    = Objects.requireNonNull(selector, "selector");
        this.pending     = Objects.requireNonNull(pending, "pending");
        this.clock       = Objects.requireNonNull(clock, "clock");
        this.metrics     = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Dispatch a command or query across the ring to a peer that advertises handling for
     * {@code payloadType}.
     *
     * @param role                 COMMAND or QUERY
     * @param payloadType          FQN of the type the peer must advertise
     * @param codecId              codec discriminator (matches the framework's outbox/payload codec)
     * @param body                 serialized payload bytes
     * @param traceId              trace id from the originator's ExecutionContext
     * @param contextCorrelationId application-level correlation
     * @param causationId          causation chain
     * @param tenantId             tenant scope, or {@code null}
     * @param timeout              maximum wait for the response — both the local pending registry's
     *                             timer AND the relative-remaining deadline sent on the wire
     * @param routingKey           optional affinity hint passed to the {@link PeerSelector}
     */
    public CompletableFuture<DispatchResponseEnvelope> dispatch(
            HandlerRole role,
            String payloadType,
            String codecId,
            byte[] body,
            UUID traceId,
            UUID contextCorrelationId,
            UUID causationId,
            @Nullable String tenantId,
            Duration timeout,
            @Nullable String routingKey) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(payloadType, "payloadType");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
        long startNanos = System.nanoTime();
        metrics.incrementDispatchRequest(role.name());

        Set<PeerId> candidates = directory.whoHandles(role, payloadType);
        if (candidates.isEmpty()) {
            return completedNotFound("no peer advertises " + role + " " + payloadType,
                                     role, startNanos);
        }
        PeerId target = selector.select(candidates, routingKey).orElse(null);
        if (target == null) {
            return completedNotFound("selector returned empty for " + role + " " + payloadType,
                                     role, startNanos);
        }
        RingConnection conn = connections.get(target).orElse(null);
        if (conn == null || conn.isClosed()) {
            return completedNotFound("no live connection to selected peer " + target,
                                     role, startNanos);
        }

        DispatchCorrelationId   correlation = DispatchCorrelationId.next();
        DispatchRequestEnvelope env         = new DispatchRequestEnvelope(
                role,
                correlation,
                localPeerId,
                payloadType,
                codecId,
                traceId,
                contextCorrelationId,
                causationId,
                tenantId,
                clock.millis(),
                timeout.toMillis(),
                body);

        CompletableFuture<DispatchResponseEnvelope> future =
                pending.register(correlation, target, timeout);

        // Wrap the future with the latency-recording side effect — the wrapped future is
        // what callers await; the underlying registry future completes via complete /
        // completeExceptionally as usual.
        CompletableFuture<DispatchResponseEnvelope> instrumented = future.whenComplete(
                                                                                       (resp, err) -> recordLatency(role, target,
                                                                                                                    payloadType, startNanos,
                                                                                                                    resp, err));

        try {
            FrameType frameType = (role == HandlerRole.COMMAND) ? FrameType.COMMAND_REQ : FrameType.QUERY_REQ;
            conn.send(RingFrame.wrapping(frameType, env.encode()));
        } catch (RuntimeException sendFailure) {
            pending.completeExceptionally(correlation, sendFailure);
        }
        return instrumented;
    }

    /**
     * Invoked by the inbound router when a COMMAND_RESP or QUERY_RESP arrives. Returns
     * {@code true} on match.
     */
    public boolean onResponse(DispatchResponseEnvelope response) {
        Objects.requireNonNull(response, "response");
        return pending.complete(response.correlationId(), response);
    }

    /**
     * Called by the connection's {@code onClosed} handler — cancels every pending dispatch
     * bound to {@code peer} with the given cause. Without this, every pending future would
     * wait its full timeout after a peer disconnects.
     */
    public int onConnectionClosed(PeerId peer, @Nullable Throwable cause) {
        Throwable c = cause != null ? cause : new java.util.concurrent.CancellationException(
                "connection to peer " + peer + " closed");
        return pending.cancelAllForPeer(peer, c);
    }

    private CompletableFuture<DispatchResponseEnvelope> completedNotFound(
            String detail, HandlerRole role, long startNanos) {
        DispatchResponseEnvelope env = DispatchResponseEnvelope.notFound(
                                                                         DispatchCorrelationId.next(), detail);
        recordLatency(role, null, "", startNanos, env, null);
        return CompletableFuture.completedFuture(env);
    }

    private void recordLatency(
            HandlerRole role,
            @Nullable PeerId target,
            String payloadType,
            long startNanos,
            @Nullable DispatchResponseEnvelope response,
            @Nullable Throwable err) {
        Duration          elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        ProtocolErrorCode code;
        if (response != null) {
            code = response.errorCode();
        } else if (err instanceof java.util.concurrent.TimeoutException) {
            code = ProtocolErrorCode.DEADLINE_EXCEEDED;
        } else {
            code = ProtocolErrorCode.INTERNAL;
        }
        metrics.recordDispatchLatency(role.name(), elapsed, code);
        var ev = new RingJfr.Dispatch();
        ev.begin();
        if (ev.shouldCommit()) {
            ev.role        = role.name();
            ev.targetPeer  = target == null ? "?" : target.value();
            ev.payloadType = payloadType;
            ev.outcome     = code.name();
            ev.commit();
        }
    }
}
