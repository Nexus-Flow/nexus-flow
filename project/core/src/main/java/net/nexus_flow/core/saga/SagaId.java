package net.nexus_flow.core.saga;

import java.util.Objects;
import java.util.UUID;
import net.nexus_flow.core.runtime.ids.FastUuid;

/**
 * Identity of a single saga instance.
 *
 * <p><strong>Uniqueness:</strong> A saga (process manager) is a long-running state machine that
 * observes events and emits compensating actions. {@link SagaId} uniquely identifies one running
 * instance within a {@link SagaStorage}.
 *
 * <p><strong>Immutability:</strong> This record wraps a {@link java.util.UUID}, making it naturally
 * immutable and suitable for use as a cache key or in distributed tracing.
 */
public record SagaId(UUID value) {

    public SagaId {
        Objects.requireNonNull(value, "value");
    }

    /** New random id. */
    public static SagaId random() {
        return new SagaId(FastUuid.v4());
    }
}
