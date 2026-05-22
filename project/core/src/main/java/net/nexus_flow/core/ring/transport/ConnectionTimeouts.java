package net.nexus_flow.core.ring.transport;

import java.time.Duration;
import java.util.Objects;

/**
 * Bundle of every per-connection timeout enforced by the transport. The framework's previous
 * shape spread the budgets across {@link RingAcceptorConfig} and {@link RingDialerConfig} —
 * with {@code handshakeTimeout} declared but never enforced. The audit identified that as a
 * high-severity DoS gap: a peer that completes TCP but never sends a HELLO would hold a
 * connection slot for the full idle window. {@code ConnectionTimeouts} is the single carrier
 * that the connection state machine consults to apply the right SO_TIMEOUT at every phase.
 *
 * @param handshake    maximum time the connection has to complete TLS + HELLO. Applied as
 *                     SO_TIMEOUT while the connection is in {@link ConnectionPhase#ACCEPTED},
 *                     {@link ConnectionPhase#TLS_HANDSHAKING}, or
 *                     {@link ConnectionPhase#PROTOCOL_HANDSHAKING}. Must be positive.
 * @param idle         maximum time between successful reads once the connection is in
 *                     {@link ConnectionPhase#AUTHENTICATED} or {@link ConnectionPhase#ACTIVE}.
 *                     {@link Duration#ZERO} disables the idle timeout (NOT recommended in
 *                     production).
 * @param write        maximum time a single frame send is allowed to take. Enforced by a
 *                     lightweight watchdog that closes the connection if the writer VT has
 *                     not advanced past a queued frame for longer than this. Must be positive.
 * @param drainOnClose maximum time {@link RingConnection#drain(java.time.Duration)} waits
 *                     for the outbound queue
 *                     to flush before transitioning to {@link ConnectionPhase#CLOSING}. May be
 *                     {@link Duration#ZERO} for "close immediately, fail queued frames".
 */
public record ConnectionTimeouts(
                                 Duration handshake,
                                 Duration idle,
                                 Duration write,
                                 Duration drainOnClose) {

    public ConnectionTimeouts {
        Objects.requireNonNull(handshake, "handshake");
        if (handshake.isNegative() || handshake.isZero()) {
            throw new IllegalArgumentException("handshake timeout must be positive: " + handshake);
        }
        Objects.requireNonNull(idle, "idle");
        if (idle.isNegative()) {
            throw new IllegalArgumentException("idle timeout must be >= 0: " + idle);
        }
        Objects.requireNonNull(write, "write");
        if (write.isNegative() || write.isZero()) {
            throw new IllegalArgumentException("write timeout must be positive: " + write);
        }
        Objects.requireNonNull(drainOnClose, "drainOnClose");
        if (drainOnClose.isNegative()) {
            throw new IllegalArgumentException("drainOnClose must be >= 0: " + drainOnClose);
        }
    }

    /** Production-ish defaults: 10s handshake, 5m idle, 10s write, 2s drain. */
    public static ConnectionTimeouts defaults() {
        return new ConnectionTimeouts(
                Duration.ofSeconds(10),
                Duration.ofMinutes(5),
                Duration.ofSeconds(10),
                Duration.ofSeconds(2));
    }

    /** Loopback-test defaults: tighter for fast feedback. */
    public static ConnectionTimeouts loopbackForTests() {
        return new ConnectionTimeouts(
                Duration.ofSeconds(2),
                Duration.ofSeconds(30),
                Duration.ofSeconds(2),
                Duration.ofSeconds(1));
    }
}
