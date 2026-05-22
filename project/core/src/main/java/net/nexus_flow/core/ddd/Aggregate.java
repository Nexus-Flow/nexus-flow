package net.nexus_flow.core.ddd;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.nexus_flow.core.cqrs.event.DomainEventContext;

/**
 * Base class for aggregate roots in the event sourcing model.
 *
 * <p><b>Event recording mechanism.</b> Each aggregate owns a private {@code uncommittedEvents}
 * list. {@link #recordEvent(DomainEvent)} appends to that list — this is the single source of truth
 * that {@link #drainEvents()} returns and that {@link #markCommitted(long)} clears.
 *
 * <p>For backwards compatibility with handlers that still observe events through the ambient {@link
 * DomainEventContext}, every {@code recordEvent} call also forwards to the context's recorder.
 * {@link #drainEvents()} clears the context unconditionally before returning so the same event is
 * never fanned out twice.
 *
 * @see AggregateRoot for the sealed interface contract
 * @see DomainEvent for event structure
 */
public abstract non-sealed class Aggregate implements AggregateRoot {

    @Serial
    private static final long serialVersionUID = 1L;

    // DomainEventContext is process-scoped infra, never serialised.
    // Marked transient to silence the [serial] warning: the field carries a
    // non-Serializable runtime collaborator and aggregates are never sent
    // across a wire; serialization of an Aggregate would be a bug anyway.
    private transient DomainEventContext eventContext = DomainEventContext.current();

    /**
     * Serialises access to aggregate-local lifecycle state.
     *
     * <p>The lock protects sequence stamping, the uncommitted-event buffer, and committed-version
     * bookkeeping so accidental concurrent {@link #recordEvent(DomainEvent)} calls cannot corrupt the
     * aggregate's internal state.
     */
    private transient Object lifecycleMonitor = new Object();

    // List<DomainEvent> is transient state; aggregates are not serialised
    // on the wire. Access is guarded by lifecycleMonitor for consistent
    // snapshots and mutation.
    private transient List<DomainEvent> uncommittedEvents = new ArrayList<>();

    /**
     * Monotonic, zero-based per-aggregate-instance counter feeding {@link
     * AbstractDomainEvent#assignSequenceNumber(long)}.
     *
     * <p>Access is serialised by {@link #lifecycleMonitor}. Aggregates are still expected to be used
     * as single-command domain objects, but the lock prevents accidental concurrent recording from
     * corrupting sequence assignment.
     */
    private long nextSequenceNumber;

    /**
     * committed {@code streamVersion} after the last successful {@code EventStore.append(...)}. Newly
     * constructed aggregates start at {@code 0}.
     */
    private long committedVersion;

    /**
     * count of events in {@link #uncommittedEvents} waiting for a {@link #markCommitted(long)} flush.
     * Tracked separately so {@link #version()} bookkeeping does not need to rescan the list.
     */
    private long uncommittedCount;

    /**
     * Creates an aggregate bound to the current {@link DomainEventContext}.
     *
     * <p>The context is captured once so subclasses and persistence adapters can extend this base
     * class without managing event-sink wiring themselves.
     */
    protected Aggregate() {
    }

    /**
     * Records each event in the supplied batch using the same sequencing and buffering guarantees as
     * {@link #recordEvent(DomainEvent)}.
     *
     * @param domainEvents events to record in encounter order; never {@code null}
     * @throws NullPointerException  if {@code domainEvents} or one of its elements is {@code null}
     * @throws IllegalStateException if a supplied {@link AbstractDomainEvent} was already recorded
     */
    @Override
    public final void recordEvent(List<DomainEvent> domainEvents) {
        Objects.requireNonNull(domainEvents, "domainEvents");
        domainEvents.forEach(this::recordEvent);
    }

    /**
     * Records a new domain event, applies it to the aggregate state, and appends it to the
     * uncommitted-event buffer.
     *
     * <p>Access is serialised so the buffer remains consistent and sequence numbers stay monotonic
     * even if callers accidentally share an aggregate instance across threads. The sequence number is
     * stamped before {@link #apply(DomainEvent)} is called, so the event carries its assigned
     * position when the state-transition hook runs. The event is only added to the uncommitted buffer
     * and made observable through the {@link DomainEventContext} after {@link #apply(DomainEvent)}
     * completes successfully; if {@code apply} throws, the sequence counter is already advanced but
     * the event never enters any external buffer.
     *
     * @param domainEvent the event to record; never {@code null}
     * @throws NullPointerException  if {@code domainEvent} is {@code null}
     * @throws IllegalStateException if an {@link AbstractDomainEvent} already has a sequence number
     */
    @Override
    public final void recordEvent(DomainEvent domainEvent) {
        Objects.requireNonNull(domainEvent, "domainEvent");
        synchronized (lifecycleMonitor) {
            if (domainEvent instanceof AbstractDomainEvent abs && abs
                    .getSequenceNumber() != AbstractDomainEvent.UNASSIGNED_SEQUENCE_NUMBER) {
                throw new IllegalStateException(
                        "DomainEvent "
                                + domainEvent.getClass().getName()
                                + " already has sequenceNumber="
                                + abs.getSequenceNumber()
                                + "; only fresh events may be recorded on an aggregate.");
            }
            // Stamp the sequence number BEFORE apply() so the event carries its
            // assigned position when the aggregate's state-transition hook runs.
            boolean counterAdvanced = false;
            if (domainEvent instanceof AbstractDomainEvent abs) {
                abs.assignSequenceNumber(nextSequenceNumber);
                nextSequenceNumber++;
                counterAdvanced = true;
            }
            // apply() runs after sequence assignment but before the event enters
            // any buffer; if apply() throws, the event is never observed externally.
            // Roll the counter back on failure so a subsequent recordEvent on this
            // aggregate does not skip a number — that gap would silently break
            // listeners that detect missing events via contiguous numbering. The
            // failed event itself stays stamped on its own instance so cross-aggregate
            // re-recording is still detected by the assignSequenceNumber guard.
            try {
                apply(domainEvent);
            } catch (Throwable t) {
                if (counterAdvanced) {
                    nextSequenceNumber--;
                }
                throw t;
            }
            uncommittedEvents.add(domainEvent);
            uncommittedCount++;
            // Forward to the ambient context so handlers that still observe events
            // through the holder (rather than draining the aggregate) keep working.
            eventContext.recordEvent(domainEvent);
        }
    }

    /**
     * Applies a domain event to this aggregate's in-memory state.
     *
     * <p>This hook is invoked for both freshly recorded events and historical replay. The default
     * implementation is a no-op; aggregates that evolve state from events override this method.
     * Implementations must not call {@link #recordEvent(DomainEvent)} recursively from here.
     *
     * @param event the event being applied to aggregate state; never {@code null}
     * @throws RuntimeException if the subclass rejects the state transition
     */
    protected void apply(DomainEvent event) {
        // default: no state transition
    }

    /**
     * Replays a historical event without adding it to the uncommitted-event buffer.
     *
     * <p>This method is used by {@code AggregateRepository.load(...)} to rebuild aggregate state and
     * to advance the next sequence number past the replayed event stream.
     *
     * @param event the historical event to replay; never {@code null}
     * @throws NullPointerException if {@code event} is {@code null}
     */
    public final void replay(DomainEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (lifecycleMonitor) {
            if (event instanceof AbstractDomainEvent abs) {
                long seq = abs.getSequenceNumber();
                if (seq >= 0 && seq >= nextSequenceNumber) {
                    nextSequenceNumber = seq + 1;
                }
            }
            apply(event);
        }
    }

    /**
     * Returns the committed stream version observed by the last successful repository save.
     *
     * @return the committed version; always {@code >= 0}
     */
    @Override
    public final long version() {
        synchronized (lifecycleMonitor) {
            return committedVersion;
        }
    }

    /**
     * Marks the currently buffered events as committed and clears the in-memory buffers.
     *
     * @param newVersion the stream version returned by the event store after a successful append
     * @throws IllegalArgumentException if {@code newVersion} does not match the expected version
     */
    @Override
    public final void markCommitted(long newVersion) {
        synchronized (lifecycleMonitor) {
            long expected = committedVersion + uncommittedCount;
            if (newVersion != expected) {
                throw new IllegalArgumentException(
                        "markCommitted: newVersion="
                                + newVersion
                                + " but committedVersion+uncommittedCount="
                                + expected
                                + " on "
                                + getClass().getName());
            }
            committedVersion = newVersion;
            uncommittedCount = 0L;
            uncommittedEvents.clear();
            eventContext.clearEvents();
        }
    }

    /**
     * Returns a stable snapshot of the aggregate's uncommitted-event buffer.
     *
     * <p>The returned list is immutable and reflects the buffer state at the instant of the call.
     * Repositories use this method before persisting events so {@link #markCommitted(long)} can still
     * validate optimistic-concurrency invariants afterwards.
     *
     * @return immutable snapshot of uncommitted events in recording order
     */
    public final List<DomainEvent> getUncommittedEvents() {
        synchronized (lifecycleMonitor) {
            return List.copyOf(uncommittedEvents);
        }
    }

    /**
     * Sets the aggregate's committed-version baseline after loading from a snapshot or event stream.
     *
     * @param version the committed stream version that the aggregate has already applied
     * @throws IllegalArgumentException if {@code version} is negative
     */
    public final void hydrateFromSnapshot(long version) {
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0: " + version);
        }
        synchronized (lifecycleMonitor) {
            this.committedVersion   = version;
            this.uncommittedCount   = 0L;
            this.nextSequenceNumber = Math.max(this.nextSequenceNumber, version);
        }
    }

    /**
     * optional state-restore hook called by {@code AggregateRepository.load(...)} when a snapshot is
     * found. Subclasses that store their domain state in {@code byte[]} (a common pattern with custom
     * serialisers) override this method to rebuild that state without replaying historical events.
     *
     * <p>{@code stateType} mirrors the snapshot's {@code stateType()} column so subclasses can branch
     * on multiple historical formats. The default is a no-op — aggregates whose entire state can be
     * rebuilt from {@code apply()} alone do not need to override it.
     *
     * @param state     opaque state bytes from the snapshot; never {@code null}
     * @param stateType identifier of the {@code state} encoding; never {@code null} or blank
     */
    public void applySnapshotState(byte[] state, String stateType) {
        // default: no-op; state is rebuilt by replay alone.
    }

    /**
     * Optionally captures snapshot state for the aggregate after a successful save.
     *
     * <p>The default returns {@link java.util.Optional#empty()}, which disables automatic snapshots
     * even when a repository has a snapshot policy configured. Subclasses that support snapshots
     * return a fresh {@link SnapshotState} containing opaque state bytes plus a matching {@code
     * stateType} understood by {@link #applySnapshotState(byte[], String)}.
     *
     * <p>Implementations must not mutate aggregate state inside this method.
     *
     * @return an optional snapshot payload to persist after commit
     */
    public java.util.Optional<SnapshotState> captureSnapshotState() {
        return java.util.Optional.empty();
    }

    /**
     * Value carrier for snapshot state captured by {@link #captureSnapshotState()}.
     *
     * <p>Mirrors the {@code state} and {@code stateType} columns of the event store's {@code
     * Snapshot} table. Subclasses use this to persist opaque state bytes along with a type tag
     * identifying the encoding format.
     *
     * @param state     opaque state bytes; never {@code null}
     * @param stateType identifier of the {@code state} encoding (e.g. "protobuf:v1", "json:v2");
     *                  never {@code null} or blank
     */
    public record SnapshotState(byte[] state, String stateType) {
        public SnapshotState {
            java.util.Objects.requireNonNull(state, "state");
            java.util.Objects.requireNonNull(stateType, "stateType");
            if (stateType.isBlank()) {
                throw new IllegalArgumentException("stateType must not be blank");
            }
            state = Arrays.copyOf(state, state.length);
        }

        @Override
        public byte[] state() {
            return Arrays.copyOf(state, state.length);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof SnapshotState(byte[] state1, String type)))
                return false;
            return Arrays.equals(state, state1) && Objects.equals(stateType, type);
        }

        @Override
        public int hashCode() {
            int result = Arrays.hashCode(state);
            result = 31 * result + Objects.hashCode(stateType);
            return result;
        }

        @Override
        public String toString() {
            return "SnapshotState{state=" + Arrays.toString(state) + ", stateType='" + stateType + "'}";
        }
    }

    /**
     * Returns the events currently visible through the ambient {@link DomainEventContext}.
     *
     * @return immutable or context-owned view of recorded events for the current execution context
     */
    @Override
    public final List<DomainEvent> getEvents() {
        return eventContext.getEvents();
    }

    /**
     * Drains the aggregate's recorded events and clears the local buffer for the next unit of work.
     *
     * <p>The returned snapshot comes from the aggregate-local buffer; the ambient {@link
     * DomainEventContext} is cleared as well so handlers that drain the aggregate cannot have those
     * same events re-emitted by any subsequent walk of the context.
     *
     * @return immutable snapshot of drained events in recording order
     */
    public final List<DomainEvent> drainEvents() {
        synchronized (lifecycleMonitor) {
            List<DomainEvent> snapshot = List.copyOf(uncommittedEvents);
            uncommittedEvents.clear();
            uncommittedCount = 0L;
            // Clearing the holder is essential: handlers that drain the aggregate
            // must not have their events re-emitted by any walk of the holder.
            eventContext.clearEvents();
            return snapshot;
        }
    }

    @Serial
    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        this.eventContext      = DomainEventContext.current();
        this.lifecycleMonitor  = new Object();
        this.uncommittedEvents = new ArrayList<>();
    }
}
