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
 *
 * <h2>Why class, not record</h2>
 *
 * <p>Was a {@code record} until the same hot-path accounting that drove {@code OutboxRecord}'s
 * conversion: the 4 {@code Objects.requireNonNull} + 2 range checks in the compact constructor
 * cost ~4-5 ns per allocation. The store's {@code append} path constructs one envelope per
 * event under the per-stream lock — every input is already validated by the surrounding
 * {@link EventStore#append} call (stream non-null, events non-null/non-empty, payload
 * non-null, versions computed inline as monotone positive). The {@link #unchecked} factory
 * lets the store skip the redundant per-envelope checks.
 */
public final class EventEnvelope {

    private final MessageId   messageId;
    private final StreamId    stream;
    private final long        streamVersion;
    private final long        globalPosition;
    private final Instant     recordedAt;
    private final DomainEvent payload;

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
    public EventEnvelope(
            MessageId messageId,
            StreamId stream,
            long streamVersion,
            long globalPosition,
            Instant recordedAt,
            DomainEvent payload) {
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.stream    = Objects.requireNonNull(stream, "stream");
        if (streamVersion < 1) {
            throw new IllegalArgumentException("streamVersion must be >= 1: " + streamVersion);
        }
        if (globalPosition < 1) {
            throw new IllegalArgumentException("globalPosition must be >= 1: " + globalPosition);
        }
        this.streamVersion  = streamVersion;
        this.globalPosition = globalPosition;
        this.recordedAt     = Objects.requireNonNull(recordedAt, "recordedAt");
        this.payload        = Objects.requireNonNull(payload, "payload");
    }

    /**
     * Private skeleton constructor — assigns fields without validation. Used by
     * {@link #unchecked} for hot-path store-append where every input is already validated.
     */
    private EventEnvelope(
            MessageId messageId,
            StreamId stream,
            long streamVersion,
            long globalPosition,
            Instant recordedAt,
            DomainEvent payload,
            @SuppressWarnings("unused") boolean uncheckedMarker) {
        this.messageId      = messageId;
        this.stream         = stream;
        this.streamVersion  = streamVersion;
        this.globalPosition = globalPosition;
        this.recordedAt     = recordedAt;
        this.payload        = payload;
    }

    /**
     * Package-private fast-path factory — bypasses validation. ONLY safe when every argument
     * is sourced from an already-validated caller; the in-tree usage is
     * {@code InMemoryEventStore.append}, which validates every input before entering the
     * per-event loop.
     */
    static EventEnvelope unchecked(
            MessageId messageId,
            StreamId stream,
            long streamVersion,
            long globalPosition,
            Instant recordedAt,
            DomainEvent payload) {
        return new EventEnvelope(messageId, stream, streamVersion, globalPosition,
                recordedAt, payload, true);
    }

    public MessageId messageId() {
        return messageId;
    }

    public StreamId stream() {
        return stream;
    }

    public long streamVersion() {
        return streamVersion;
    }

    public long globalPosition() {
        return globalPosition;
    }

    public Instant recordedAt() {
        return recordedAt;
    }

    public DomainEvent payload() {
        return payload;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EventEnvelope other)) {
            return false;
        }
        return streamVersion == other.streamVersion && globalPosition == other.globalPosition && Objects.equals(messageId,
                                                                                                                other.messageId) && Objects
                                                                                                                        .equals(stream,
                                                                                                                                other.stream) && Objects
                                                                                                                                        .equals(recordedAt,
                                                                                                                                                other.recordedAt) && Objects
                                                                                                                                                        .equals(payload,
                                                                                                                                                                other.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, stream, streamVersion, globalPosition, recordedAt, payload);
    }

    @Override
    public String toString() {
        return "EventEnvelope["
                + "messageId=" + messageId
                + ", stream=" + stream
                + ", streamVersion=" + streamVersion
                + ", globalPosition=" + globalPosition
                + ", recordedAt=" + recordedAt
                + ", payload=" + payload
                + ']';
    }
}
