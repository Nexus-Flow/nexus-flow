package net.nexus_flow.core.ring.wire;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Streaming decoder that turns a sequence of arbitrary-sized byte chunks into discrete
 * {@link RingFrame} instances. Maintains state across calls so a frame's header or body split
 * across multiple {@link #tryDecode(ByteBuffer, Consumer)} invocations is reassembled
 * correctly.
 *
 * <h2>Threading</h2>
 *
 * Single-owner: one decoder per connection, used exclusively by the one thread reading from
 * that connection. NOT thread-safe.
 *
 * <h2>State machine</h2>
 *
 * <pre>
 * READING_HEADER --(12 bytes accumulated)--&gt; READING_BODY
 * READING_BODY --(body length accumulated)--&gt; EMIT + RESET to READING_HEADER
 * </pre>
 *
 * On any protocol violation the decoder throws — the connection cannot recover the byte stream
 * after a malformed frame and MUST be closed.
 *
 * <h2>Incremental body allocation</h2>
 *
 * The earlier version of the decoder allocated the full announced body up-front. That allowed
 * a peer to force {@code maxBodyBytes × connections} allocation just by sending headers — a
 * classic length-prefixed pre-allocation attack. This decoder grows the body buffer
 * incrementally:
 *
 * <ol>
 * <li>The first body byte triggers allocation of a {@code min(bodyLen, initialBodyChunk)}
 * buffer.
 * <li>As the buffer fills, it grows by doubling (capped at the announced body length).
 * <li>Per-decoder pending-bytes (header + body buffer) is accounted via {@link #pendingBytes()}
 * so the owning transport can bound TOTAL pre-allocated bytes across all connections.
 * </ol>
 *
 * <h2>Backpressure on pre-allocation</h2>
 *
 * The transport SHOULD call {@link #pendingBytes()} during accept-rate evaluation. If the
 * sum of {@code pendingBytes()} across all open decoders exceeds the global decode budget,
 * the transport refuses new accepts. This is the global-bound complement to the per-frame
 * {@code maxBodyBytes} cap.
 */
public final class FrameDecoder {

    /** First-touch body allocation — sized to a single small frame; grows by doubling. */
    private static final int INITIAL_BODY_CHUNK = 4 * 1024;

    private enum State {
        READING_HEADER,
        READING_BODY
    }

    private final int        maxBodyBytes;
    private final ByteBuffer header;
    private State            state = State.READING_HEADER;
    private FrameType        pendingType;
    private int              pendingBodyLen;
    private byte[]           pendingBody;
    private int              pendingBodyOffset;

    /**
     * Construct a decoder with a configurable body-size cap.
     *
     * @param maxBodyBytes the maximum body length accepted from a peer; values &gt; this trigger
     *                     {@link FrameTooLargeException} and the connection should be closed.
     *                     Must be {@code &gt; 0}.
     * @throws IllegalArgumentException if {@code maxBodyBytes &lt;= 0}
     */
    public FrameDecoder(int maxBodyBytes) {
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("maxBodyBytes must be > 0: " + maxBodyBytes);
        }
        this.maxBodyBytes = maxBodyBytes;
        this.header       = ByteBuffer.allocate(RingProtocol.HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
    }

    /** Construct a decoder with the framework default body-size cap. */
    public FrameDecoder() {
        this(RingProtocol.DEFAULT_MAX_BODY_BYTES);
    }

    /**
     * Consume bytes from {@code in} (between its current {@code position} and {@code limit}),
     * emitting every complete frame to {@code sink}. Returns when {@code in} is exhausted (a
     * partial frame is buffered internally for the next call).
     *
     * @throws RingProtocolException  on bad magic, unknown version, unknown frame type, or any
     *                                other framing-layer violation
     * @throws FrameTooLargeException if a frame's announced body length exceeds the decoder's
     *                                {@code maxBodyBytes}
     */
    public void tryDecode(ByteBuffer in, Consumer<RingFrame> sink) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(sink, "sink");
        while (in.hasRemaining()) {
            switch (state) {
                case READING_HEADER -> {
                    if (!fillFromInput(in, header)) {
                        return;
                    }
                    parseHeader();
                    if (pendingBodyLen == 0) {
                        sink.accept(new RingFrame(pendingType, FrameBody.empty()));
                        resetForNextFrame();
                    } else {
                        state = State.READING_BODY;
                    }
                }
                case READING_BODY   -> {
                    int needed = pendingBodyLen - pendingBodyOffset;
                    int take   = Math.min(needed, in.remaining());
                    ensureBodyCapacity(pendingBodyOffset + take);
                    in.get(pendingBody, pendingBodyOffset, take);
                    pendingBodyOffset += take;
                    if (pendingBodyOffset == pendingBodyLen) {
                        // Trim the body to its exact length and hand ownership to the frame —
                        // no defensive clone, the decoder will not retain the array.
                        byte[] owned;
                        if (pendingBody.length == pendingBodyLen) {
                            owned = pendingBody;
                        } else {
                            owned = new byte[pendingBodyLen];
                            System.arraycopy(pendingBody, 0, owned, 0, pendingBodyLen);
                        }
                        sink.accept(new RingFrame(pendingType, FrameBody.ofOwned(owned)));
                        resetForNextFrame();
                    }
                }
            }
        }
    }

    /** Bytes currently buffered awaiting more input — for global decode-budget accounting. */
    public int pendingBytes() {
        int headerPending = state == State.READING_HEADER ? header.position() : 0;
        int bodyPending   = pendingBody == null ? 0 : pendingBodyOffset;
        return headerPending + bodyPending;
    }

    /** @return the configured maximum body length. */
    public int maxBodyBytes() {
        return maxBodyBytes;
    }

    private boolean fillFromInput(ByteBuffer in, ByteBuffer dst) {
        int take = Math.min(dst.remaining(), in.remaining());
        if (take == 0) {
            return !dst.hasRemaining();
        }
        int oldLimit = in.limit();
        in.limit(in.position() + take);
        dst.put(in);
        in.limit(oldLimit);
        return !dst.hasRemaining();
    }

    private void parseHeader() {
        header.flip();
        byte[] magic = new byte[RingProtocol.MAGIC_BYTES];
        header.get(magic);
        if (!Arrays.equals(magic, RingProtocol.MAGIC)) {
            throw new RingProtocolException(
                    "bad magic: got " + Arrays.toString(magic) + ", expected NXFR");
        }
        byte version = header.get();
        if (version != RingProtocol.VERSION) {
            throw new RingProtocolException(
                    "unsupported wire version " + (version & 0xFF) + ", expected " + RingProtocol.VERSION);
        }
        byte typeCode = header.get();
        pendingType = FrameType.requireFromWireCode(typeCode);
        header.getShort(); // reserved flags
        int bodyLen = header.getInt();
        if (bodyLen < 0) {
            throw new RingProtocolException("negative body length: " + bodyLen);
        }
        if (bodyLen > maxBodyBytes) {
            throw new FrameTooLargeException(bodyLen, maxBodyBytes);
        }
        pendingBodyLen    = bodyLen;
        pendingBody       = null; // lazy — allocated on first body byte via ensureBodyCapacity
        pendingBodyOffset = 0;
        header.clear();
    }

    private void ensureBodyCapacity(int needed) {
        if (pendingBody == null) {
            int initial = Math.min(pendingBodyLen, INITIAL_BODY_CHUNK);
            // For frames smaller than the initial chunk allocate exactly the announced size;
            // for larger frames start at INITIAL_BODY_CHUNK and grow by doubling.
            pendingBody = new byte[Math.max(initial, needed)];
            return;
        }
        if (needed <= pendingBody.length) {
            return;
        }
        int next = pendingBody.length;
        while (next < needed) {
            // Double; clamp to announced length so the buffer never exceeds the legitimate body.
            next = Math.min(next * 2, pendingBodyLen);
        }
        if (next < needed) {
            next = needed;
        }
        byte[] grown = new byte[next];
        System.arraycopy(pendingBody, 0, grown, 0, pendingBodyOffset);
        pendingBody = grown;
    }

    private void resetForNextFrame() {
        state             = State.READING_HEADER;
        pendingType       = null;
        pendingBodyLen    = 0;
        pendingBody       = null;
        pendingBodyOffset = 0;
        header.clear();
    }
}
