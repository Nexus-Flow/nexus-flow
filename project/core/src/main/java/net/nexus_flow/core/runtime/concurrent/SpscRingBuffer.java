package net.nexus_flow.core.runtime.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

/**
 * Lock-free single-producer single-consumer (SPSC) ring buffer with padded cursors to
 * eliminate false sharing.
 *
 * <h2>Use case</h2>
 *
 * Hand-off queue between exactly ONE producer thread and exactly ONE consumer thread —
 * the dominant pattern in workers, encoders, and per-connection pipelines. Avoids the
 * lock-acquire and CAS-loop costs of a {@link java.util.concurrent.ArrayBlockingQueue}
 * (or even an MPMC structure) when the producer/consumer cardinality is statically 1.
 *
 * <h2>Capacity</h2>
 *
 * Power-of-two only — the index masking step ({@code & (capacity - 1)}) replaces the
 * modulo on every offer/poll. Constructor rounds {@code requestedCapacity} UP to the next
 * power of two; the buffer holds up to {@code capacity - 1} elements (one slot is reserved
 * to disambiguate "empty" from "full" without a separate count field).
 *
 * <h2>Memory model</h2>
 *
 * <ul>
 * <li>{@code head} (read cursor) is written ONLY by the consumer; read by both via
 * acquire-load.
 * <li>{@code tail} (write cursor) is written ONLY by the producer; read by both via
 * acquire-load.
 * <li>Slot writes use {@code setRelease} so the consumer's {@code getAcquire} sees the
 * stored reference happens-before the cursor advance.
 * <li>Cache-line padding around both cursors (128 bytes ≈ two x86 cache lines) eliminates
 * false sharing when producer + consumer pin to different cores.
 * </ul>
 *
 * <h2>Limitations</h2>
 *
 * <ul>
 * <li>Single producer + single consumer ONLY. Behaviour with multiple producers OR
 * multiple consumers is undefined (silent data races on the cursor stores).
 * <li>{@code offer} returns {@code false} when the buffer is full — callers decide
 * whether to back off, block, drop, or grow.
 * <li>{@code poll} returns {@code null} when the buffer is empty — callers decide
 * whether to spin, park, or yield.
 * <li>{@code size()} is a best-effort snapshot — head/tail are read independently, so
 * a concurrent offer/poll between the two reads can yield a stale count. Use only
 * for monitoring / telemetry, NOT for atomicity decisions.
 * </ul>
 *
 * <h2>Adapter modules</h2>
 *
 * Future Disruptor-style multi-consumer pipelines OR LMAX-pattern queues can be built on
 * top of this primitive by composing per-consumer cursors. The framework's outbox /
 * dispatcher / ring-transport components consume this primitive directly when their
 * cardinality matches.
 *
 * @param <E> element type — must be non-null on offer; nulls reserved as the empty
 *            sentinel by {@link #poll()}
 */
public final class SpscRingBuffer<E> {

    /**
     * Cache-line padding — 128 bytes covers two x86 cache lines (modern CPUs prefetch
     * line N+1 alongside N). Surrounding both cursors with padding eliminates false
     * sharing when the producer and consumer pin to different cores.
     */
    @SuppressWarnings("unused")
    private long p1, p2, p3, p4, p5, p6, p7;

    /** Producer cursor — only the producer thread writes. */
    private volatile long tail;

    @SuppressWarnings("unused")
    private long p9, p10, p11, p12, p13, p14, p15;

    /** Consumer cursor — only the consumer thread writes. */
    private volatile long head;

    @SuppressWarnings("unused")
    private long p17, p18, p19, p20, p21, p22, p23;

    private final Object[] buffer;
    private final int      mask;

    private static final VarHandle TAIL;
    private static final VarHandle HEAD;
    private static final VarHandle ELEMENTS = MethodHandles.arrayElementVarHandle(Object[].class);

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            TAIL = lookup.findVarHandle(SpscRingBuffer.class, "tail", long.class);
            HEAD = lookup.findVarHandle(SpscRingBuffer.class, "head", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Construct a buffer with capacity rounded UP to the next power of two.
     *
     * @param requestedCapacity desired capacity; must be {@code >= 2}
     * @throws IllegalArgumentException if {@code requestedCapacity < 2} or rounds above
     *                                  {@code 2^30} (the cap that keeps the index math overflow-free)
     */
    public SpscRingBuffer(int requestedCapacity) {
        if (requestedCapacity < 2) {
            throw new IllegalArgumentException("requestedCapacity must be >= 2: " + requestedCapacity);
        }
        int rounded = nextPowerOfTwo(requestedCapacity);
        if (rounded > 1 << 30) {
            throw new IllegalArgumentException(
                    "requestedCapacity exceeds 2^30: " + requestedCapacity);
        }
        this.buffer = new Object[rounded];
        this.mask   = rounded - 1;
    }

    /**
     * Producer-side enqueue. Non-blocking; returns {@code false} when the buffer is full.
     *
     * <p>Wait-free: a single acquire-load + bounds check + plain store + release-store.
     * No CAS, no lock, no allocation.
     *
     * @param element the element to enqueue; must not be {@code null}
     * @return {@code true} on success, {@code false} when full
     * @throws NullPointerException if {@code element} is {@code null}
     */
    public boolean offer(E element) {
        Objects.requireNonNull(element, "element");
        long t = tail;
        long h = (long) HEAD.getAcquire(this);
        // Full when the producer would catch the consumer (one slot reserved for the
        // empty/full disambiguator).
        if (t - h >= buffer.length) {
            return false;
        }
        int slot = (int) (t & mask);
        ELEMENTS.setRelease(buffer, slot, element);
        TAIL.setRelease(this, t + 1);
        return true;
    }

    /**
     * Consumer-side dequeue. Non-blocking; returns {@code null} when the buffer is empty.
     *
     * <p>Wait-free: a single acquire-load + bounds check + acquire-load + plain store +
     * release-store.
     *
     * @return the next element, or {@code null} when empty
     */
    @SuppressWarnings("unchecked")
    public E poll() {
        long h = head;
        long t = (long) TAIL.getAcquire(this);
        if (h >= t) {
            return null;
        }
        int slot    = (int) (h & mask);
        E   element = (E) ELEMENTS.getAcquire(buffer, slot);
        if (element == null) {
            // Producer reserved the cursor but has not yet released the slot — visible as
            // a transient gap. Returning null tells the consumer to retry on its next
            // poll; we do NOT advance head until the slot is materialised.
            return null;
        }
        // Null out the slot so the GC can reclaim the element after the consumer
        // releases the reference.
        ELEMENTS.setRelease(buffer, slot, null);
        HEAD.setRelease(this, h + 1);
        return element;
    }

    /**
     * @return best-effort snapshot of the current element count; head/tail are read
     *         independently so the value may be stale by the time it is returned. Use for
     *         monitoring / telemetry, NOT for atomicity decisions.
     */
    public int size() {
        long h = (long) HEAD.getAcquire(this);
        long t = (long) TAIL.getAcquire(this);
        return (int) Math.max(0L, t - h);
    }

    /** @return the configured capacity (power-of-two, rounded up from the request). */
    public int capacity() {
        return buffer.length;
    }

    /** @return {@code true} when {@link #poll} would currently return {@code null}. */
    public boolean isEmpty() {
        return ((long) HEAD.getAcquire(this)) >= ((long) TAIL.getAcquire(this));
    }

    private static int nextPowerOfTwo(int n) {
        return n <= 1 ? 1 : Integer.highestOneBit(n - 1) << 1;
    }
}
