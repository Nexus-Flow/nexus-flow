package net.nexus_flow.core.ring.transport;

/**
 * Explicit lifecycle phase of a {@link RingConnection}. Replaces the original {@code
 * AtomicBoolean closed} flag with a state machine that lets every transport invariant
 * (timeouts, observability, close cause classification, send rejection) key off the same
 * source of truth.
 *
 * <h2>Phase transitions</h2>
 *
 * <pre>
 * ACCEPTED ──tls enabled──&gt; TLS_HANDSHAKING ──ok──┐
 * │ │
 * └────────tls disabled──&gt;──────────────────┐│
 * ││
 * ▼▼
 * PROTOCOL_HANDSHAKING
 * │
 * handshake done
 * ▼
 * AUTHENTICATED
 * │
 * bindPeerId
 * ▼
 * ACTIVE
 * │
 * drainOnShutdown │ close() called
 * ▼─────────────┴────────────┐
 * ▼
 * CLOSING
 * │
 * ▼
 * CLOSED
 * </pre>
 *
 * <p>{@link #DRAINING} is an optional middle step a caller can request before {@link #CLOSING}
 * if it wants the writer VT to flush queued frames. The transport will not enter {@link
 * #DRAINING} on its own — only explicit {@link RingConnection#drain(java.time.Duration)} does — because
 * timeout-driven and error-driven closes go straight to {@link #CLOSING}.
 *
 * <h2>What each phase enforces</h2>
 *
 * <ul>
 * <li><b>ACCEPTED / TLS_HANDSHAKING / PROTOCOL_HANDSHAKING</b> — SO_TIMEOUT is the
 * per-connection handshake budget. Sends from outside the handshake handler are
 * rejected; outside callers don't know this connection yet.
 * <li><b>AUTHENTICATED / ACTIVE</b> — SO_TIMEOUT is the steady-state idle budget. Sends are
 * admitted; reader/writer VTs run normally.
 * <li><b>DRAINING</b> — sends rejected; writer continues until the queue is empty or the
 * drain budget expires.
 * <li><b>CLOSING / CLOSED</b> — sends rejected with the close cause; reader/writer VTs are
 * on their way out.
 * </ul>
 *
 * <h2>Ordering invariant</h2>
 *
 * Phases never move backwards. {@link #ordinal()} corresponds to the strict order above; the
 * connection's state-machine code uses {@code newPhase.ordinal() > current.ordinal()} as the
 * monotonicity guard. The enum values must therefore stay in this exact order — adding a phase
 * means inserting it at the right ordinal slot, not appending to the end.
 */
public enum ConnectionPhase {

    /** TCP connection has been accepted/dialed; no TLS or protocol bytes exchanged yet. */
    ACCEPTED,

    /** mTLS handshake in progress (only entered when TLS is configured). */
    TLS_HANDSHAKING,

    /** TLS done (or skipped); waiting for HELLO / HELLO_ACK exchange. */
    PROTOCOL_HANDSHAKING,

    /** HELLO/HELLO_ACK complete; principal extracted from TLS where applicable. */
    AUTHENTICATED,

    /** Peer id bound; steady-state. */
    ACTIVE,

    /** Caller-requested graceful drain: stop accepting new sends, flush queue, then close. */
    DRAINING,

    /** Close started; reader/writer VTs are unwinding. */
    CLOSING,

    /** All resources released. Terminal. */
    CLOSED;

    /**
     * {@code true} for phases where the connection is admitting outbound sends. Includes
     * {@link #PROTOCOL_HANDSHAKING} because the handshake handler legitimately sends HELLO /
     * HELLO_ACK before the peer id is bound — without that we'd have to either route handshake
     * sends through a side channel or split the SPI into "before-auth" and "after-auth"
     * variants. {@link #ACCEPTED} and {@link #TLS_HANDSHAKING} reject sends because the
     * application-level streams are not yet stable (TLS handshake bytes are wire-level, not
     * application data).
     */
    public boolean acceptsSends() {
        return this == PROTOCOL_HANDSHAKING || this == AUTHENTICATED || this == ACTIVE;
    }

    /** {@code true} for the two pre-AUTHENTICATED handshake phases. */
    public boolean isHandshaking() {
        return this == ACCEPTED || this == TLS_HANDSHAKING || this == PROTOCOL_HANDSHAKING;
    }

    /** {@code true} for {@link #CLOSING} and {@link #CLOSED}. */
    public boolean isClosing() {
        return this == CLOSING || this == CLOSED;
    }
}
