package net.nexus_flow.core.runtime.ids;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Stable identifier of a single {@code Command}, {@code Query} or {@code Event} dispatch instance.
 *
 * <p>Used as the seed for the {@link CausationId} of any nested message: a child dispatch carries
 * its parent's {@code MessageId} as its own {@code CausationId}.
 *
 * <p><strong>Generation source.</strong> {@link #random()} returns a v4 UUID drawn from {@link
 * java.util.concurrent.ThreadLocalRandom} — wire-format-identical to {@link UUID#randomUUID()} but
 * ~10× cheaper. {@code MessageId} is an observability handle, not a security token; the 122 random
 * bits are vastly more than needed for deduplication and the source of those bits does not need to
 * be cryptographically strong. The hot path (one {@code MessageId} per listener per event in
 * fan-out) is sensitive to the per-call cost. Callers that genuinely need a {@link
 * java.security.SecureRandom}-backed UUID construct it explicitly via {@code UUID.randomUUID()} and
 * pass it to the constructor.
 *
 * <h2>Why a class instead of a record</h2>
 *
 * Was a {@code record(UUID value)} until JMH measured that {@link #asCausation()} accounts for
 * 1.16 ns per child dispatch on the hot fan-out path — the cost of an unavoidable
 * {@code new CausationId(value)} on every call. The class shape adds a {@code volatile} lazy
 * cache so the second and subsequent {@link #asCausation()} calls return the cached
 * {@link CausationId} (~7× faster per JMH). Records cannot declare mutable fields and so the
 * memoisation requires this conversion.
 *
 * <p>The public surface is preserved byte-for-byte:
 * <ul>
 * <li>{@code new MessageId(UUID)} constructor — unchanged
 * <li>{@link #value()} accessor — unchanged
 * <li>{@link #equals(Object)} / {@link #hashCode()} — defined by {@link UUID#equals(Object)} /
 * {@link UUID#hashCode()}, structurally identical to the record's auto-generated form
 * <li>{@link #toString()} — unchanged
 * <li>{@link #random()} factory — unchanged
 * <li>{@link #asCausation()} — unchanged semantics, now memoised
 * </ul>
 *
 * <p>The {@code cachedCausation} field is {@code transient} — serialised form contains only the
 * UUID, so deserialised instances cold-start with an empty cache.
 */
public final class MessageId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID                               value;
    /**
     * Lazy-computed {@link CausationId} mirroring {@link #value}. Field is {@code volatile}
     * so the double-checked publication is safe — readers see either {@code null} (and
     * compute one fresh) or the published instance. The benign race (two threads each
     * compute and one wins the last write) wastes at most one allocation, which is the same
     * cost the un-cached path paid per call anyway.
     */
    private transient volatile @Nullable CausationId cachedCausation;

    public MessageId(UUID value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    /** @return the underlying UUID. */
    public UUID value() {
        return value;
    }

    /**
     * New random {@code MessageId} backed by a v4 UUID drawn from {@link
     * java.util.concurrent.ThreadLocalRandom}. See class Javadoc for the security trade-off.
     *
     * @return a fresh {@code MessageId} with a v4 UUID
     */
    public static MessageId random() {
        return new MessageId(FastUuid.v4());
    }

    /**
     * Promote this {@code MessageId} into the {@link CausationId} of a child dispatch. The
     * returned instance is cached for the lifetime of THIS {@code MessageId}; the second and
     * subsequent calls return the same {@link CausationId} reference.
     *
     * @return a {@link CausationId} whose underlying UUID equals this message's UUID
     */
    public CausationId asCausation() {
        CausationId c = cachedCausation;
        if (c == null) {
            c               = new CausationId(value);
            cachedCausation = c;
        }
        return c;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MessageId other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
