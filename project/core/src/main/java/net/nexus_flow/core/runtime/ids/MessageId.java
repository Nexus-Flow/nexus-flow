package net.nexus_flow.core.runtime.ids;

import java.util.Objects;
import java.util.UUID;

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
 * pass it to the record constructor.
 */
public record MessageId(UUID value) {
    public MessageId {
        Objects.requireNonNull(value, "value");
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
     * Promote this {@code MessageId} into the {@link CausationId} of a child dispatch.
     *
     * @return a {@link CausationId} whose underlying UUID equals this message's UUID
     */
    public CausationId asCausation() {
        return new CausationId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
