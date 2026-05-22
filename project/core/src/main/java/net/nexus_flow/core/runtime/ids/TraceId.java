package net.nexus_flow.core.runtime.ids;

import java.util.Objects;
import java.util.UUID;

/**
 * Trace identifier shared across an entire end-to-end flow.
 *
 * <p>Constant from the root dispatch through every nested command/query/event handler. Intended to
 * map directly onto an OpenTelemetry trace id once a telemetry exporter is in place.
 */
public record TraceId(UUID value) {
    public TraceId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * New random {@code TraceId} backed by a v4 UUID drawn from {@link
     * java.util.concurrent.ThreadLocalRandom}. See {@link MessageId} for the security trade-off
     * (observability handle, not a security token).
     *
     * @return a fresh {@code TraceId} with a v4 UUID
     */
    public static TraceId random() {
        return new TraceId(FastUuid.v4());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
