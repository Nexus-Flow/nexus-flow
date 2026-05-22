package net.nexus_flow.core.ring.observability;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import net.nexus_flow.core.observability.MetricsRecorder;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.wire.FrameType;
import net.nexus_flow.core.ring.wire.ProtocolErrorCode;

/**
 * Facade over the framework's {@link MetricsRecorder} that names every ring-level metric and
 * fixes its label cardinality.
 *
 * <h2>Why a facade</h2>
 *
 * The ring layer's metric surface flows through this single facade so the catalog stays
 * consistent across the codebase. Instead of sprinkling raw {@code
 * metrics.incrementCounter("foo", Map.of(...))} calls throughout the ring code — where typos,
 * label-cardinality bugs, and inconsistent naming would creep in — every ring metric goes
 * through here. Metric names are frozen constants; tag values are filtered through the
 * cardinality rules described below.
 *
 * <h2>Tag cardinality rules (skill §19)</h2>
 *
 * Tags that are SAFE to use as metric dimensions:
 * <ul>
 * <li>{@code transport} — {@code "tcp"} or {@code "tls"}; cardinality 2.
 * <li>{@code role} — {@code "client"} (dialer) or {@code "server"} (acceptor); cardinality 2.
 * <li>{@code frameType} — small enum (currently 15); cardinality bounded.
 * <li>{@code outcome} — small enum: success, failure, timeout, backpressure, protocol_error.
 * <li>{@code errorCode} — {@link ProtocolErrorCode} enum; cardinality bounded.
 * <li>{@code phase} — {@link net.nexus_flow.core.ring.transport.ConnectionPhase}; cardinality
 * bounded.
 * <li>{@code peerId} — typically bounded by deployment size (≤ thousands), but can spike
 * under churn. Only used for low-volume metrics (joins/leaves/lease changes).
 * </ul>
 *
 * NEVER used as tags: {@code traceId}, {@code correlationId}, {@code messageId}, raw exception
 * messages, remote IP/port (use {@code peerId} instead).
 *
 * <h2>Threading</h2>
 *
 * Thread-safe — the underlying {@link MetricsRecorder} is the contract carrier; this class
 * holds no mutable state.
 */
public final class RingMetrics {

    /** Metric name constants — used by tests to assert specific metrics fire. */
    public static final String ACCEPT_TOTAL = "ring.accept.total";

    public static final String ACCEPT_REJECTED_CAPACITY = "ring.accept.rejected.capacity";
    public static final String ACCEPT_REJECTED_RATE     = "ring.accept.rejected.rate_limited";
    public static final String ACCEPT_FAILURE_TOTAL     = "ring.accept.failure.total";
    public static final String CONNECT_TOTAL            = "ring.connect.total";
    public static final String CONNECT_FAILURE_TOTAL    = "ring.connect.failure.total";
    public static final String CONNECTIONS_ACTIVE       = "ring.connections.active";
    public static final String TLS_HANDSHAKE_DURATION   = "ring.tls.handshake.duration";
    public static final String HANDSHAKE_TIMEOUT_TOTAL  = "ring.handshake.timeout.total";
    public static final String IDLE_TIMEOUT_TOTAL       = "ring.idle.timeout.total";
    public static final String WRITE_TIMEOUT_TOTAL      = "ring.write.timeout.total";
    public static final String CLOSE_TOTAL              = "ring.close.total";
    public static final String FRAMES_READ              = "ring.frames.read";
    public static final String FRAMES_WRITTEN           = "ring.frames.written";
    public static final String BYTES_READ               = "ring.bytes.read";
    public static final String BYTES_WRITTEN            = "ring.bytes.written";
    public static final String DECODE_FAILURE_TOTAL     = "ring.decode.failure.total";
    public static final String PROTOCOL_VIOLATION       = "ring.protocol.violation";
    public static final String OUTBOUND_QUEUE_BYTES     = "ring.outbound.queue.bytes";
    public static final String BACKPRESSURE_REJECTED    = "ring.backpressure.rejected";
    public static final String DISPATCH_REQUEST_TOTAL   = "ring.dispatch.request.total";
    public static final String DISPATCH_RESPONSE_TOTAL  = "ring.dispatch.response.total";
    public static final String DISPATCH_LATENCY         = "ring.dispatch.latency";
    public static final String DISPATCH_CANCELLED       = "ring.dispatch.cancelled";
    public static final String DISPATCH_TIMEOUT         = "ring.dispatch.timeout";
    public static final String DISPATCH_FORBIDDEN       = "ring.dispatch.forbidden";
    public static final String AUTHZ_DENIED             = "ring.authz.denied";
    public static final String OUTBOX_FANOUT_TOTAL      = "ring.outbox.fanout.total";
    public static final String OUTBOX_FANOUT_FAILED     = "ring.outbox.fanout.failed";
    public static final String OUTBOX_REPLAY_TOTAL      = "ring.outbox.replay.total";
    public static final String SAGA_LEASE_TRANSITION    = "ring.saga.lease.transition";
    public static final String SAGA_OWNERSHIP_REJECTED  = "ring.saga.ownership.rejected";

    private final MetricsRecorder     recorder;
    private final String              localPeerId;
    /**
     * Cached single-entry tag map for the no-extra-tag path. {@link Map#of(Object, Object)}
     * allocates per call; this cache pays once per metric instance.
     */
    private final Map<String, String> cachedBaseTags;

    /**
     * @param recorder    the underlying sink; typically the {@link
     *                    net.nexus_flow.core.observability.Observability#metrics()} of the
     *                    {@code FlowRuntime}. Pass {@link MetricsRecorder#NO_OP} when no
     *                    observability is configured.
     * @param localPeerId this pod's id; used as a static {@code localPeer} tag on every metric
     *                    so multi-pod dashboards can group by source
     */
    public RingMetrics(MetricsRecorder recorder, String localPeerId) {
        this.recorder       = Objects.requireNonNull(recorder, "recorder");
        this.localPeerId    = Objects.requireNonNull(localPeerId, "localPeerId");
        this.cachedBaseTags = Map.of("localPeer", this.localPeerId);
    }

    /** Convenience for tests / call sites that don't have a peer id yet. */
    public static RingMetrics noOp() {
        return new RingMetrics(MetricsRecorder.NO_OP, "unknown");
    }

    public void incrementAccept(String transport) {
        recorder.incrementCounter(ACCEPT_TOTAL, baseTags("transport", safeTransport(transport)));
    }

    public void incrementAcceptCapacityRejection() {
        recorder.incrementCounter(ACCEPT_REJECTED_CAPACITY, baseTags());
    }

    public void incrementAcceptRateRejection() {
        recorder.incrementCounter(ACCEPT_REJECTED_RATE, baseTags());
    }

    public void incrementAcceptFailure() {
        recorder.incrementCounter(ACCEPT_FAILURE_TOTAL, baseTags());
    }

    public void incrementConnect(String transport, boolean success) {
        recorder.incrementCounter(
                                  CONNECT_TOTAL,
                                  baseTags(
                                           "transport", safeTransport(transport),
                                           "outcome", success ? "success" : "failure"));
        if (!success) {
            recorder.incrementCounter(CONNECT_FAILURE_TOTAL, baseTags("transport", safeTransport(transport)));
        }
    }

    public void recordTlsHandshake(Duration d, boolean success) {
        recorder.recordTimer(TLS_HANDSHAKE_DURATION, d, baseTags("outcome", success ? "success" : "failure"));
    }

    public void incrementHandshakeTimeout() {
        recorder.incrementCounter(HANDSHAKE_TIMEOUT_TOTAL, baseTags());
    }

    public void incrementIdleTimeout() {
        recorder.incrementCounter(IDLE_TIMEOUT_TOTAL, baseTags());
    }

    public void incrementWriteTimeout() {
        recorder.incrementCounter(WRITE_TIMEOUT_TOTAL, baseTags());
    }

    public void incrementClose(String causeCategory) {
        recorder.incrementCounter(CLOSE_TOTAL, baseTags("cause", causeCategory));
    }

    public void incrementFramesRead(FrameType type) {
        recorder.incrementCounter(FRAMES_READ, baseTags("frameType", type.name()));
    }

    public void incrementFramesWritten(FrameType type) {
        recorder.incrementCounter(FRAMES_WRITTEN, baseTags("frameType", type.name()));
    }

    public void recordActiveConnections(int n) {
        recorder.recordGauge(CONNECTIONS_ACTIVE, n, baseTags());
    }

    public void recordBytesRead(int n) {
        recorder.recordGauge(BYTES_READ, n, baseTags());
    }

    public void recordBytesWritten(int n) {
        recorder.recordGauge(BYTES_WRITTEN, n, baseTags());
    }

    public void recordOutboundQueueBytes(long bytes) {
        recorder.recordGauge(OUTBOUND_QUEUE_BYTES, bytes, baseTags());
    }

    public void incrementBackpressureRejected() {
        recorder.incrementCounter(BACKPRESSURE_REJECTED, baseTags());
    }

    public void incrementDecodeFailure() {
        recorder.incrementCounter(DECODE_FAILURE_TOTAL, baseTags());
    }

    public void incrementProtocolViolation() {
        recorder.incrementCounter(PROTOCOL_VIOLATION, baseTags());
    }

    public void incrementDispatchRequest(String role) {
        recorder.incrementCounter(DISPATCH_REQUEST_TOTAL, baseTags("role", role));
    }

    public void recordDispatchLatency(String role, Duration d, ProtocolErrorCode outcome) {
        recorder.recordTimer(
                             DISPATCH_LATENCY,
                             d,
                             baseTags("role", role, "outcome", outcome.name()));
        recorder.incrementCounter(
                                  DISPATCH_RESPONSE_TOTAL,
                                  baseTags("role", role, "outcome", outcome.name()));
    }

    public void incrementDispatchCancelled() {
        recorder.incrementCounter(DISPATCH_CANCELLED, baseTags());
    }

    public void incrementDispatchTimeout() {
        recorder.incrementCounter(DISPATCH_TIMEOUT, baseTags());
    }

    public void incrementDispatchForbidden() {
        recorder.incrementCounter(DISPATCH_FORBIDDEN, baseTags());
    }

    public void incrementAuthzDenied(String role) {
        recorder.incrementCounter(AUTHZ_DENIED, baseTags("role", role));
    }

    public void incrementOutboxFanout(boolean success) {
        recorder.incrementCounter(
                                  OUTBOX_FANOUT_TOTAL, baseTags("outcome", success ? "success" : "failure"));
        if (!success) {
            recorder.incrementCounter(OUTBOX_FANOUT_FAILED, baseTags());
        }
    }

    public void incrementOutboxReplay() {
        recorder.incrementCounter(OUTBOX_REPLAY_TOTAL, baseTags());
    }

    public void incrementSagaLeaseTransition(String fromOwner, String toOwner) {
        // Owner ids are bounded by deployment size and are used in low-volume lease events
        recorder.incrementCounter(
                                  SAGA_LEASE_TRANSITION, baseTags("from", fromOwner, "to", toOwner));
    }

    public void incrementSagaOwnershipRejected(PeerId sender) {
        // peerId cardinality bounded by ring size
        recorder.incrementCounter(SAGA_OWNERSHIP_REJECTED, baseTags("sender", sender.value()));
    }

    private Map<String, String> baseTags() {
        return cachedBaseTags;
    }

    private Map<String, String> baseTags(String k, String v) {
        return Map.of("localPeer", localPeerId, k, v);
    }

    private Map<String, String> baseTags(String k1, String v1, String k2, String v2) {
        return Map.of("localPeer", localPeerId, k1, v1, k2, v2);
    }

    private static String safeTransport(String t) {
        return ("tcp".equals(t) || "tls".equals(t)) ? t : "unknown";
    }
}
