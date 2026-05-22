package net.nexus_flow.core.ring.dispatch;

import java.util.Objects;
import java.util.UUID;
import net.nexus_flow.core.runtime.ids.FastUuid;

/**
 * Correlation handle that ties a {@link DispatchRequestEnvelope} to its matching
 * {@link DispatchResponseEnvelope}. Distinct newtype (not raw {@link UUID}) so the compiler
 * catches accidental swaps with other UUID-shaped ids (trace, message, query handles).
 *
 * <p>Allocated via {@link FastUuid#v4()} — a correlation id is an internal observability
 * handle, not a security token, and the framework's id-allocation policy lets the cheap
 * non-secure path serve every internal correlation use.
 */
public record DispatchCorrelationId(UUID value) {

    public DispatchCorrelationId {
        Objects.requireNonNull(value, "value");
    }

    /** Allocate a fresh correlation id. Cheap — backed by {@code ThreadLocalRandom}. */
    public static DispatchCorrelationId next() {
        return new DispatchCorrelationId(FastUuid.v4());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
