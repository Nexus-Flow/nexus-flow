package net.nexus_flow.core.outbox;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * stable identity of a row in {@link OutboxStorage}.
 *
 * <p>Backed by a UUID whose generator preserves monotonic ordering within a single JVM by combining
 * the current wall-clock millis with a JVM-wide atomic counter. The format is loosely inspired by
 * UUIDv7 (time-ordered); does not commit to a precise on-disk encoding so the storage backends
 * remain free to use whatever native id representation makes sense (e.g. {@code BIGSERIAL} on
 * Postgres). The only contract callers can rely on is:
 *
 * <ul>
 * <li>{@link OutboxId#next()} is thread-safe and lock-free.
 * <li>For any two ids {@code a, b} produced on the same JVM where {@code a} was minted strictly
 * before {@code b}, the natural ordering satisfies {@code a.compareTo(b) < 0}.
 * </ul>
 */
public record OutboxId(UUID value) implements Comparable<OutboxId> {

    private static final AtomicLong MONOTONIC_COUNTER = new AtomicLong();

    public OutboxId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Mint a new {@code OutboxId} monotonically ordered against every previous id minted on this JVM.
     */
    public static OutboxId next() {
        long now = System.currentTimeMillis();
        long seq = MONOTONIC_COUNTER.incrementAndGet();
        // High 48 bits of msb: wall-clock millis (sufficient until year 10889).
        // Low 16 bits of msb: high half of the JVM-wide counter — keeps order
        // stable within the same millisecond.
        long msb = (now << 16) | ((seq >>> 48) & 0xFFFFL);
        // Low 64 bits: remaining counter bits + random tail for uniqueness
        // across JVMs (the counter is JVM-local).
        long lsb = (seq << 16) | (ThreadLocalRandom.current().nextInt() & 0xFFFFL);
        return new OutboxId(new UUID(msb, lsb));
    }

    @Override
    public int compareTo(OutboxId o) {
        int hi =
                Long.compareUnsigned(this.value.getMostSignificantBits(), o.value.getMostSignificantBits());
        if (hi != 0)
            return hi;
        return Long.compareUnsigned(
                                    this.value.getLeastSignificantBits(), o.value.getLeastSignificantBits());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
