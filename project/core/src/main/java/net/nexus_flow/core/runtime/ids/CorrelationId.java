package net.nexus_flow.core.runtime.ids;

import java.util.Objects;
import java.util.UUID;

/**
 * Business-level correlation identifier.
 *
 * <p>Constant across an entire conceptual operation, including any retries or async hops. Distinct
 * from {@link TraceId}, which is technical and tied to a single execution graph.
 */
public record CorrelationId(UUID value) {
    public CorrelationId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * New random {@code CorrelationId} backed by a v4 UUID drawn from {@link
     * java.util.concurrent.ThreadLocalRandom}. See {@link MessageId} for the security trade-off
     * (observability handle, not a security token).
     *
     * @return a fresh {@code CorrelationId} with a v4 UUID
     */
    public static CorrelationId random() {
        return new CorrelationId(FastUuid.v4());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
