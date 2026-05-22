package net.nexus_flow.core.ring.wire;

import java.io.Serial;

/**
 * Specialisation of {@link RingProtocolException} thrown when a peer announces a body length
 * that exceeds the receiver's configured cap. Surfaced as its own type because operators want
 * to discriminate "peer is broken / sending garbage" (generic protocol exception) from "peer is
 * trying to send legitimate but oversized data" (capacity-planning signal — maybe the cap
 * needs to be raised, or maybe upstream serialisation produced an outlier message).
 */
public final class FrameTooLargeException extends RingProtocolException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int announcedBytes;
    private final int maxBytes;

    /**
     * @param announcedBytes the body length the peer wrote in the frame header
     * @param maxBytes       the receiver's configured maximum body length
     */
    public FrameTooLargeException(int announcedBytes, int maxBytes) {
        super(
              "frame body length "
                      + announcedBytes
                      + " bytes exceeds receiver cap of "
                      + maxBytes
                      + " bytes — closing connection");
        this.announcedBytes = announcedBytes;
        this.maxBytes       = maxBytes;
    }

    /** @return the body length the peer announced */
    public int announcedBytes() {
        return announcedBytes;
    }

    /** @return the receiver's configured cap */
    public int maxBytes() {
        return maxBytes;
    }
}
