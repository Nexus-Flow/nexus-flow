package net.nexus_flow.core.ring.observability;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * Container for the ring transport's custom JFR event classes. JFR events are always-on:
 * when no recording is active the JVM skips both allocation and {@link Event#commit()}
 * write, so the overhead on the hot path is negligible. When a recording IS active these
 * events give post-hoc forensic flight data with no external dependency.
 *
 * <h2>Why nested classes inside one container</h2>
 *
 * Every ring JFR event shares the same {@link Category}; keeping them together makes the
 * event taxonomy obvious from one file. Each event is its own top-level JFR type — the
 * nesting is just a Java-package shape, not a JFR shape.
 *
 * <h2>Commit pattern</h2>
 *
 * <pre>{@code
 * var ev = new RingJfr.ConnectionLifecycle();
 * ev.begin();
 * // ... do work ...
 * if (ev.shouldCommit()) {
 *     ev.peerId = peerId.value();
 *     ev.phase  = phase.name();
 *     ev.commit();
 * }
 * }</pre>
 *
 * String fields are populated only after {@link Event#shouldCommit()} returns {@code true},
 * matching the framework's existing JFR-emission convention (see {@link
 * net.nexus_flow.core.observability.jfr}).
 */
public final class RingJfr {

    /**
     * Shared JFR category labels for every ring event. Exposed as constants so downstream
     * tooling (custom JFR views, dashboards) can filter on the same strings the
     * {@link jdk.jfr.Category} annotations use, without copy-pasting the literal.
     */
    public static final String CATEGORY_ROOT = "Nexus Flow";

    public static final String CATEGORY_RING      = "Ring";
    public static final String CATEGORY_TRANSPORT = "Transport";
    public static final String CATEGORY_WIRE      = "Wire";
    public static final String CATEGORY_DISPATCH  = "Dispatch";
    public static final String CATEGORY_SAGA      = "Saga";
    public static final String CATEGORY_OUTBOX    = "Outbox";

    private RingJfr() {
        // Type-only container — instantiation is not the intended usage. Nested event
        // classes are the JFR surface.
    }

    /** Connection lifecycle phase transitions. */
    @Name("net.nexus_flow.ring.ConnectionLifecycle")
    @Label("Ring Connection Lifecycle")
    @Category({"Nexus Flow", "Ring", "Transport"})
    @Description("Phase transitions of a single ring connection (accepted/handshake/active/closing/closed).")
    public static class ConnectionLifecycle extends Event {
        @Label("Peer ID")
        public String peerId;

        @Label("Remote Address")
        public String remoteAddress;

        @Label("Phase")
        public String phase;

        @Label("Transport")
        public String transport;
    }

    /** Single frame decoded or written. */
    @Name("net.nexus_flow.ring.Frame")
    @Label("Ring Frame")
    @Category({"Nexus Flow", "Ring", "Wire"})
    @Description("One framed message read from or written to a ring connection.")
    public static class Frame extends Event {
        @Label("Direction")
        public String direction; // "read" | "write"

        @Label("Frame Type")
        public String frameType;

        @Label("Body Bytes")
        public int bodyBytes;

        @Label("Peer ID")
        public String peerId;
    }

    /** Cross-pod dispatch (command/query) round-trip. */
    @Name("net.nexus_flow.ring.Dispatch")
    @Label("Ring Dispatch")
    @Category({"Nexus Flow", "Ring", "Dispatch"})
    @Description("Cross-pod command/query dispatch round-trip.")
    public static class Dispatch extends Event {
        @Label("Role")
        public String role; // COMMAND | QUERY

        @Label("Target Peer")
        public String targetPeer;

        @Label("Payload Type")
        public String payloadType;

        @Label("Outcome")
        public String outcome; // SUCCESS | FAILURE | DEADLINE_EXCEEDED | NOT_FOUND | …
    }

    /** Saga lease ownership transition. */
    @Name("net.nexus_flow.ring.SagaLeaseTransition")
    @Label("Saga Lease Transition")
    @Category({"Nexus Flow", "Ring", "Saga"})
    @Description("Saga lease ownership changed (renewal, claim, or rejection).")
    public static class SagaLeaseTransition extends Event {
        @Label("Saga ID")
        public String sagaId;

        @Label("From")
        public String fromOwner;

        @Label("To")
        public String toOwner;

        @Label("Outcome")
        public String outcome; // RENEWED | CLAIMED | REJECTED_AUTHORIZATION
    }

    /** Outbox row fanned out to ring peers. */
    @Name("net.nexus_flow.ring.OutboxFanout")
    @Label("Outbox Fan-Out")
    @Category({"Nexus Flow", "Ring", "Outbox"})
    @Description("One outbox row fanned out to ring peers (counted across all peers reached).")
    public static class OutboxFanout extends Event {
        @Label("Outbox Sequence")
        public long outboxSequence;

        @Label("Peers Reached")
        public int peersReached;

        @Label("Peers Failed")
        public int peersFailed;

        @Label("Payload Bytes")
        public int payloadBytes;
    }

    /** Backpressure / authorization / protocol-violation rejection. */
    @Name("net.nexus_flow.ring.Rejection")
    @Label("Ring Rejection")
    @Category({"Nexus Flow", "Ring", "Backpressure"})
    @Description("A frame was refused on entry (backpressure, authorization, protocol violation).")
    public static class Rejection extends Event {
        @Label("Reason")
        public String reason; // BACKPRESSURE | FORBIDDEN | PROTOCOL_VIOLATION | CAPACITY_EXCEEDED | RATE_LIMITED

        @Label("Frame Type")
        public String frameType;

        @Label("Peer ID")
        public String peerId;
    }
}
