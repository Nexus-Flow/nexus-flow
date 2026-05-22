package net.nexus_flow.core.eventsourcing;

import java.time.Instant;
import java.util.Objects;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ids.MessageId;

/**
 * Read-side view of a single event recorded in an {@link EventStore}.
 *
 * <p>The envelope wraps a {@link DomainEvent} with the two ordering coordinates that the store
 * guarantees:
 *
 * <ul>
 * <li>{@link #streamVersion()} — monotonic 1-based position within the {@link #stream()}. The
 * first event of a stream has {@code streamVersion == 1}.
 * <li>{@link #globalPosition()} — monotonic 1-based position across the entire store. Used by
 * {@code Projection} runners to fold multiple streams into a single read model.
 * </ul>
 *
 * <p>The {@link #messageId()} mirrors the runtime {@link net.nexus_flow.core.runtime.ids.MessageId}
 * stamped at dispatch time and is the same identity carried by the outbox ({@link
 * net.nexus_flow.core.outbox.OutboxRecord#messageId()}). The envelope is immutable.
 */
public record EventEnvelope(
                            MessageId messageId,
                            StreamId stream,
                            long streamVersion,
                            long globalPosition,
                            Instant recordedAt,
                            DomainEvent payload) {

    /**
     * Create an immutable event envelope.
     *
     * @param messageId      stable message identity for the recorded event
     * @param stream         stream that owns the event
     * @param streamVersion  1-based stream position
     * @param globalPosition 1-based global position
     * @param recordedAt     timestamp assigned by the store
     * @param payload        domain event payload
     * @throws NullPointerException     if any reference argument is {@code null}
     * @throws IllegalArgumentException if {@code streamVersion < 1} or {@code globalPosition < 1}
     */
    public EventEnvelope {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(stream, "stream");
        if (streamVersion < 1) {
            throw new IllegalArgumentException("streamVersion must be >= 1: " + streamVersion);
        }
        if (globalPosition < 1) {
            throw new IllegalArgumentException("globalPosition must be >= 1: " + globalPosition);
        }
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(payload, "payload");
    }
}
