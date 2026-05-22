package net.nexus_flow.core.ring.transport;

import java.io.Serial;
import net.nexus_flow.core.ring.wire.RingFrame;

/**
 * Thrown when a frame cannot be enqueued for sending because the per-connection outbound queue
 * is full and the policy is to fail fast rather than block.
 *
 * <p>Per the {@code nexus-java-network-io-lowlevel} skill §11: outbound queues MUST be bounded
 * and the rejection MUST be visible at the caller. Silent buffering hides slow peers until p99
 * collapses; explicit failure surfaces the saturation in metrics and lets the higher layer
 * decide (drop, downgrade, reroute via another peer).
 *
 * <p>This is a runtime exception because the dispatch path threads it through
 * {@code DispatchResult.Failure} at the boundary — propagating a checked exception across
 * every {@code send} call site would force every caller to either retry inline (defeating the
 * fail-fast intent) or wrap in try/catch (ceremonial noise).
 */
public final class RingBackpressureRejectedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient PeerId peerId;
    private final int              outboundQueueDepth;
    private final int              outboundQueueCapacity;

    /**
     * @param peerId                the peer whose outbound queue is full
     * @param outboundQueueDepth    current queue depth at rejection time
     * @param outboundQueueCapacity the configured capacity that was exceeded
     * @param frame                 the frame that triggered the rejection — included in the message tail for
     *                              diagnostics (frame type + body size, NOT the body bytes themselves)
     */
    public RingBackpressureRejectedException(
            PeerId peerId, int outboundQueueDepth, int outboundQueueCapacity, RingFrame frame) {
        super(
              "ring outbound queue saturated for peer "
                      + peerId
                      + ": depth="
                      + outboundQueueDepth
                      + " capacity="
                      + outboundQueueCapacity
                      + " rejectedFrame="
                      + frame.type()
                      + " bodyBytes="
                      + frame.body().length());
        this.peerId                = peerId;
        this.outboundQueueDepth    = outboundQueueDepth;
        this.outboundQueueCapacity = outboundQueueCapacity;
    }

    public PeerId peerId() {
        return peerId;
    }

    public int outboundQueueDepth() {
        return outboundQueueDepth;
    }

    public int outboundQueueCapacity() {
        return outboundQueueCapacity;
    }
}
