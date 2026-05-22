package net.nexus_flow.core.eventsourcing;

import java.util.List;
import java.util.Objects;

/**
 * Slice of envelopes returned by {@link EventStore#read(StreamId, long, long)} or {@link
 * EventStore#readAll(long, long)}.
 *
 * <p>For a <strong>single-stream read</strong>, {@link #lastVersion()} is the {@code streamVersion}
 * of the last envelope, or {@code 0} when the slice is empty.
 *
 * <p>For a <strong>global read</strong> ({@link EventStore#readAll}), {@link #lastVersion()} holds
 * the {@link EventEnvelope#globalPosition()} of the last envelope — {@link
 * net.nexus_flow.core.eventsourcing.InMemoryEventStore} sets it to {@code
 * slice.getLast().globalPosition()}. Callers such as {@code ProjectionRunner} use {@code
 * slice.lastVersion() + 1} as the next {@code fromGlobalPosition}.
 */
public record EventStream(List<EventEnvelope> events, long lastVersion) {

    /**
     * Create an immutable event-stream slice.
     *
     * @param events      envelopes contained in this page
     * @param lastVersion stream version or global position of the final envelope, depending on the
     *                    read API that produced the slice
     * @throws NullPointerException     if {@code events} is {@code null}
     * @throws IllegalArgumentException if {@code lastVersion < 0}
     */
    public EventStream {
        Objects.requireNonNull(events, "events");
        events = List.copyOf(events);
        if (lastVersion < 0) {
            throw new IllegalArgumentException("lastVersion must be >= 0: " + lastVersion);
        }
    }

    /**
     * Return whether this slice contains no envelopes.
     *
     * @return {@code true} when {@link #events()} is empty
     */
    public boolean isEmpty() {
        return events.isEmpty();
    }

    /**
     * Return the number of envelopes in this slice.
     *
     * @return envelope count
     */
    public int size() {
        return events.size();
    }

    /**
     * Create an empty stream slice.
     *
     * @return empty slice whose {@link #lastVersion()} is {@code 0}
     */
    public static EventStream empty() {
        return new EventStream(List.of(), 0L);
    }
}
