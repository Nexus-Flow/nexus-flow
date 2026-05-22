package net.nexus_flow.core.runtime.ids;

import java.util.Objects;
import java.util.UUID;
import net.nexus_flow.core.runtime.ExecutionContext;

/**
 * Identifier of the message that <em>caused</em> the current dispatch.
 *
 * <p>For root dispatches the value is the same UUID as the message itself (or zero, by convention);
 * for nested dispatches it equals the parent message's {@link MessageId}. See {@link
 * ExecutionContext#childContextFor(MessageId)}.
 */
public record CausationId(UUID value) {
    public CausationId {
        Objects.requireNonNull(value, "value");
    }

    /** Sentinel causation used by the very first message in a flow (no parent). */
    public static final CausationId ROOT = new CausationId(new UUID(0L, 0L));

    @Override
    public String toString() {
        return value.toString();
    }
}
