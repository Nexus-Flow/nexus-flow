package net.nexus_flow.core.ring.wire;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Immutable, zero-copy view over the bytes of a frame body.
 *
 * <p>The hot path of the ring transport encodes and decodes millions of frames per second per
 * pod. A canonical {@code byte[]} accessor that defensively clones (the easy correctness shape
 * for value records) inflates the per-frame allocation count by 3-5x, which is the dominant GC
 * pressure on the dispatch hot path. {@code FrameBody} is the carrier the codec and writer use
 * INSTEAD of {@code byte[]}, exposing only safe accessors:
 *
 * <ul>
 * <li>{@link #length()} — bytes available; cheap, no allocation.
 * <li>{@link #asReadOnlyBuffer()} — a fresh {@link ByteBuffer} view that aliases the backing
 * array. The backing array MUST NOT be mutated after construction; callers gain a
 * read-only window for parsing.
 * <li>{@link #writeTo(ByteBuffer)} — bulk copy into the writer's pooled encode buffer; no
 * intermediate {@code byte[]} allocation.
 * <li>{@link #copyToByteArray()} — escape hatch for adapters that genuinely need a byte
 * array (e.g. legacy serdes); not used on the hot path.
 * </ul>
 *
 * <h2>Ownership rules</h2>
 *
 * <ol>
 * <li>The factory {@link #ofOwned(byte[])} takes ownership of the array — the caller MUST
 * NOT retain or mutate it after the call.
 * <li>The factory {@link #ofCopy(byte[], int, int)} copies the range — caller may continue
 * to use its array.
 * <li>The factory {@link #empty()} returns a shared singleton — no allocation per use.
 * <li>Once constructed, a {@code FrameBody} is structurally immutable from any safe code
 * path: {@link #asReadOnlyBuffer()} returns a read-only view; no accessor exposes the
 * backing array.
 * </ol>
 *
 * <h2>Why not a record</h2>
 *
 * A record's compact constructor cannot enforce "do not retain the source array"; the only
 * defence is to clone, which is precisely the allocation cost this type exists to avoid. A
 * final class with explicit factory methods names the ownership rules as part of the API.
 */
public final class FrameBody {

    private static final byte[] EMPTY = new byte[0];

    /** Shared empty body — zero-length frames reuse this instance, avoiding per-frame allocation. */
    public static final FrameBody EMPTY_BODY = new FrameBody(EMPTY, 0, 0);

    private final byte[] backing;
    private final int    offset;
    private final int    length;

    private FrameBody(byte[] backing, int offset, int length) {
        this.backing = backing;
        this.offset  = offset;
        this.length  = length;
    }

    /**
     * Wrap {@code bytes} as a body without copying. The caller hands over ownership of the array
     * — once this method returns, the caller MUST NOT mutate or retain a reference to the array.
     * Use {@link #ofCopy(byte[], int, int)} when ownership cannot be transferred.
     *
     * @param bytes the byte array to take ownership of; must not be {@code null}
     * @return a body backed by {@code bytes}
     */
    public static FrameBody ofOwned(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) {
            return EMPTY_BODY;
        }
        return new FrameBody(bytes, 0, bytes.length);
    }

    /**
     * Defensive copy of {@code bytes[offset..offset+length)}. Use when the caller needs to retain
     * the source array.
     *
     * @param bytes  the source array; must not be {@code null}
     * @param offset start offset within the source
     * @param length number of bytes to copy; {@code 0} returns {@link #EMPTY_BODY}
     * @return a body containing a fresh copy of the range
     */
    public static FrameBody ofCopy(byte[] bytes, int offset, int length) {
        Objects.requireNonNull(bytes, "bytes");
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + " length=" + length + " arrayLength=" + bytes.length);
        }
        if (length == 0) {
            return EMPTY_BODY;
        }
        byte[] copy = new byte[length];
        System.arraycopy(bytes, offset, copy, 0, length);
        return new FrameBody(copy, 0, length);
    }

    /** @return the canonical zero-length body */
    public static FrameBody empty() {
        return EMPTY_BODY;
    }

    /** @return the number of bytes in this body */
    public int length() {
        return length;
    }

    /**
     * A read-only {@link ByteBuffer} view of the body bytes. The buffer aliases the backing
     * array; modifications via the original array would be visible — the ownership contract of
     * the factory methods is the only safeguard. Allocates ONE wrapper object (no payload copy).
     */
    public ByteBuffer asReadOnlyBuffer() {
        return ByteBuffer.wrap(backing, offset, length).asReadOnlyBuffer();
    }

    /**
     * Append every byte of this body to {@code dst}. Zero per-byte allocations; uses the bulk
     * {@code ByteBuffer.put(byte[], int, int)} path.
     *
     * @throws java.nio.BufferOverflowException if {@code dst} has fewer than {@link #length()}
     *                                          bytes remaining
     */
    public void writeTo(ByteBuffer dst) {
        Objects.requireNonNull(dst, "dst");
        dst.put(backing, offset, length);
    }

    /**
     * Read-only {@link MemorySegment} view sliced to {@code [offset, offset + length)} of the
     * backing array. Aliases the same heap region as {@link #asReadOnlyBuffer()} — pick this
     * accessor when the consumer is going to issue
     * {@link MemorySegment#copy(MemorySegment, long, MemorySegment, long, long)} so the
     * destination receives bytes through the JIT-intrinsified copy path instead of the
     * {@code ByteBuffer} bulk-put loop. Returned segment is {@code asReadOnly()} so the caller
     * cannot mutate the underlying array through it.
     */
    public MemorySegment asMemorySegment() {
        return MemorySegment.ofArray(backing).asSlice(offset, length).asReadOnly();
    }

    /**
     * Escape hatch — returns a fresh {@code byte[]} containing every byte of this body. Used by
     * adapters that integrate with APIs requiring a byte array (legacy serdes, hashing). Not
     * called on the framing/transport hot path.
     */
    public byte[] copyToByteArray() {
        if (length == 0) {
            return EMPTY;
        }
        byte[] copy = new byte[length];
        System.arraycopy(backing, offset, copy, 0, length);
        return copy;
    }

    /**
     * Bit-pattern equality. Two bodies of the same length with the same bytes compare equal even
     * when their backing arrays are distinct instances. Hashing iterates the body bytes once.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FrameBody other) || other.length != this.length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (backing[offset + i] != other.backing[other.offset + i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (int i = 0; i < length; i++) {
            h = 31 * h + backing[offset + i];
        }
        return h;
    }

    @Override
    public String toString() {
        return "FrameBody[" + length + " bytes]";
    }
}
