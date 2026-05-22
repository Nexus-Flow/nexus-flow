package net.nexus_flow.core.ddd;

import java.io.*;
import java.time.Instant;
import java.util.*;

/**
 * Abstract base class for domain events in the event sourcing model.
 *
 * <p>This class provides the standard implementation of {@link DomainEvent} with support for
 * per-aggregate-instance sequence numbering, metadata headers, and idempotency key derivation.
 *
 * <p>Subclasses must call one of the protected constructors to initialize the event with its
 * aggregate ID and optional metadata. The event's sequence number is assigned by {@link
 * Aggregate#recordEvent(DomainEvent)} and is used to ensure stable ordering and deduplication
 * across the domain.
 *
 * @see DomainEvent for the interface contract
 * @see Aggregate#recordEvent(DomainEvent)
 */
public abstract non-sealed class AbstractDomainEvent implements DomainEvent, Serializable {

    /**
     * Serial version UID for wire-format compatibility.
     *
     * <p>Required for {@link net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec} to
     * round-trip events through the outbox. The class shape is frozen by this constant; bumping this
     * ID is a backwards-incompatible change to the wire format.
     */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Explicit serial form, decoupled from Java field names.
     *
     * <p>This pins the on-wire shape of the class so future Java-field renames (or storage-layer
     * reshuffles) never break previously persisted payloads. The custom {@link #writeObject} / {@link
     * #readObject} methods read and write through these names exclusively — default serialization is
     * never used.
     *
     * <p>Field meanings:
     *
     * <ul>
     * <li>{@code id} — UUID assigned at construction.
     * <li>{@code occurredAt} — {@link Instant} when the event was created.
     * <li>{@code aggregateId} — owning aggregate identifier.
     * <li>{@code sequenceNumber} — per-aggregate sequence stamped by {@code Aggregate.recordEvent}.
     * </ul>
     *
     * <p>{@code headers} are intentionally <em>not</em> serialized: they are runtime-only metadata
     * (correlation/trace/tenant identifiers) and must be re-derived from the current context after
     * deserialization.
     */
    @Serial
    private static final ObjectStreamField[] serialPersistentFields = {new ObjectStreamField("id", UUID.class), new ObjectStreamField(
            "occurredAt", Instant.class), new ObjectStreamField("aggregateId", String.class), new ObjectStreamField("sequenceNumber",
                    Long.TYPE),
    };

    /**
     * Sentinel value marking an event that has not yet been stamped with a per-aggregate-instance
     * sequence number.
     */
    static final long UNASSIGNED_SEQUENCE_NUMBER = -1L;

    /** Unique identifier assigned when the event instance is created. */
    private final UUID id;

    /**
     * Immutable occurrence instant captured during construction.
     *
     * <p>This is the event's {@code occurredAt} timestamp and never changes for the lifetime of the
     * event instance.
     */
    private final Instant occurredAt;

    /**
     * Stable identifier of the aggregate instance that emitted this event.
     *
     * <p>The value is captured at construction time and reused by {@link #idempotencyKey()} once the
     * event receives its aggregate-local sequence number.
     */
    private final String aggregateId;

    /**
     * Monotonic, per-aggregate-instance sequence number assigned by {@code
     * Aggregate.recordEvent(...)}. Stays at {@link #UNASSIGNED_SEQUENCE_NUMBER} until the event is
     * recorded.
     *
     * <p>The field is {@code volatile} so a concurrent listener reading {@link #idempotencyKey()}
     * observes the assigned value even if the recording and the publication happen on different
     * carrier threads. The runtime still guarantees record-then-publish ordering for the common case;
     * {@code volatile} is a defensive additional safeguard.
     */
    private volatile long sequenceNumber = UNASSIGNED_SEQUENCE_NUMBER;

    /**
     * Cached result of {@link #idempotencyKey()}. Computed once after the sequence number is
     * assigned, then served verbatim on every subsequent call. The hot path (one {@code
     * idempotencyKey()} call per listener per dispatch) is the dominant call site; without this cache
     * each invocation paid for a fresh {@code aggregateId + ":" + sequenceNumber} String
     * concatenation. With it the 2nd-and-subsequent calls are O(1) zero-alloc.
     *
     * <p>The race condition is benign: two threads that compute the key concurrently produce
     * identical strings (the inputs are immutable post-stamp), so a lost-update only costs a
     * redundant allocation, never a wrong value. The field is {@code volatile} so the cache result
     * publishes safely across threads. Marked {@code transient} because serialization recomputes the
     * key from {@link #aggregateId} and {@link #sequenceNumber} on demand — caching it on the wire
     * would just bloat the payload.
     */
    private transient volatile @org.jspecify.annotations.Nullable String cachedIdempotencyKey;

    /**
     * Arbitrary metadata attached to this event (e.g. correlation-id, trace-id, tenant-id).
     *
     * <p>The map is populated via the constructors and is intentionally immutable once set
     * (copy-on-create semantics). Never {@code null}; defaults to an empty map.
     *
     * <p>Marked {@code transient}: events are dispatched in-process and headers are runtime metadata
     * that should not be persisted through Java serialization.
     */
    private transient Map<String, String> headers;

    /**
     * Constructs a domain event with the given aggregate ID.
     *
     * <p>No metadata headers are attached; use {@link #AbstractDomainEvent(String, Map)} to supply
     * headers.
     *
     * @param aggregateId the owning aggregate's unique identifier; never {@code null}
     * @throws NullPointerException if {@code aggregateId} is {@code null}
     */
    protected AbstractDomainEvent(String aggregateId) {
        this(aggregateId, Collections.emptyMap());
    }

    /**
     * Constructs a domain event with the given aggregate ID and metadata headers.
     *
     * @param aggregateId the owning aggregate's unique identifier; never {@code null}
     * @param headers     key-value metadata copied defensively; never {@code null}
     * @throws NullPointerException if {@code aggregateId} or {@code headers} is {@code null}
     */
    protected AbstractDomainEvent(String aggregateId, Map<String, String> headers) {
        this.id          = net.nexus_flow.core.runtime.ids.FastUuid.v4();
        this.occurredAt  = Instant.now();
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(headers, "headers");
        // Fast path for the overwhelmingly common case (the no-args constructor passes
        // emptyMap; most event subclasses never carry per-instance metadata). Skip the
        // LinkedHashMap + unmodifiableMap wrapper allocations entirely.
        this.headers = headers.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    /**
     * Returns the unique identifier assigned to this concrete event instance.
     *
     * @return the event identifier; never {@code null}
     */
    @Override
    public UUID getId() {
        return this.id;
    }

    /**
     * Returns the immutable instant when this event was created.
     *
     * <p>This value is captured once during construction and acts as the event's {@code occurredAt}
     * timestamp.
     *
     * @return the event occurrence timestamp; never {@code null}
     */
    @Override
    public Instant getTimestamp() {
        return this.occurredAt;
    }

    /**
     * Returns the stable aggregate identifier captured when the event was constructed.
     *
     * @return the owning aggregate identifier; never {@code null}
     */
    @Override
    public String getAggregateId() {
        return this.aggregateId;
    }

    /**
     * Returns the immutable metadata headers attached to this event.
     *
     * <p>This is the package's lightweight event-metadata mechanism for propagating values such as
     * correlation identifiers, trace identifiers, and tenant identifiers without introducing a
     * separate {@code EventMetadata} type.
     *
     * <p><strong>Persistence caveat — headers are NOT durable.</strong> The {@code headers} field is
     * {@code transient}: the outbox round-trip (encode → store → decode by {@link
     * net.nexus_flow.core.outbox.OutboxWorker}) reconstructs the event with an EMPTY headers map. The
     * canonical trace / correlation / causation / tenant IDs travel via the {@link
     * net.nexus_flow.core.outbox.OutboxRecord} fields and are restored into the rebuilt {@link
     * net.nexus_flow.core.runtime.ExecutionContext} on replay, so observability remains intact — but
     * any application-specific metadata you stash in {@code headers} is lost on replay. Consumers
     * that need durable per-event metadata persist it as a typed field on the domain-event subclass
     * (which the {@link net.nexus_flow.core.outbox.OutboxPayloadCodec} will encode along with the
     * rest of the payload).
     *
     * @return the headers map; never {@code null}, may be empty
     */
    @Override
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * Returns the per-aggregate-instance, zero-based sequence number stamped by {@code
     * Aggregate.recordEvent(...)}. Returns {@link #UNASSIGNED_SEQUENCE_NUMBER} for events constructed
     * outside an aggregate (e.g. fixtures in unit tests) until they are recorded.
     *
     * @return the sequence number, or {@code -1L} if not yet recorded
     */
    public final long getSequenceNumber() {
        return sequenceNumber;
    }

    /**
     * Package-private hook invoked exactly once by {@code Aggregate.recordEvent(DomainEvent)} when
     * the event is appended to that aggregate's uncommitted list. Calling it twice is a programming
     * error and surfaces as {@link IllegalStateException} — that happens only if the same event
     * instance is recorded on two aggregates (a contract violation: events are one-shot).
     *
     * @param seq the sequence number to assign; must be non-negative
     * @throws IllegalStateException    if the event already has a sequence number assigned
     * @throws IllegalArgumentException if {@code seq} is negative
     */
    final void assignSequenceNumber(long seq) {
        if (sequenceNumber != UNASSIGNED_SEQUENCE_NUMBER) {
            throw new IllegalStateException(
                    "DomainEvent "
                            + getClass().getName()
                            + " (id="
                            + id
                            + ") already has sequenceNumber="
                            + sequenceNumber
                            + "; cannot reassign to "
                            + seq
                            + " — events are one-shot, recording on two aggregates is forbidden.");
        }
        if (seq < 0) {
            throw new IllegalArgumentException("sequenceNumber must be non-negative: " + seq);
        }
        sequenceNumber = seq;
    }

    /**
     * Returns the canonical idempotency key derived from this event's aggregate ID and sequence
     * number.
     *
     * <p>The default key is stable after the first successful {@link
     * Aggregate#recordEvent(DomainEvent)} call because {@link #aggregateId} is immutable and {@link
     * #sequenceNumber} is assigned exactly once. Subclasses representing external events whose
     * deduplication handle comes from an upstream system must override this method instead of relying
     * on the default derivation.
     *
     * @return the deduplication key in the form {@code aggregateId:sequenceNumber}
     * @throws UnsupportedOperationException if the event was never stamped with a sequence number
     *                                       (i.e. constructed outside an aggregate and not overridden)
     */
    @Override
    public String idempotencyKey() {
        // Fast path: cached after first compute. Volatile read with no fence cost on x86 / ARM
        // beyond the memory ordering already needed for the underlying sequenceNumber read.
        String cached = cachedIdempotencyKey;
        if (cached != null) {
            return cached;
        }
        if (sequenceNumber == UNASSIGNED_SEQUENCE_NUMBER) {
            throw new UnsupportedOperationException(
                    "event lacks idempotencyKey: "
                            + getClass().getName()
                            + " was not recorded on an aggregate; override idempotencyKey() "
                            + "or record() the event before publishing.");
        }
        // Race-tolerant: two concurrent threads that miss the cache produce identical Strings
        // (aggregateId is final, sequenceNumber is one-shot), so a lost-update on the volatile
        // write just discards one redundant allocation. The cache invariant holds either way.
        String computed = aggregateId + ":" + sequenceNumber;
        cachedIdempotencyKey = computed;
        return computed;
    }

    @Serial
    private void writeObject(ObjectOutputStream out) throws IOException {
        ObjectOutputStream.PutField fields = out.putFields();
        fields.put("id", this.id);
        fields.put("occurredAt", this.occurredAt);
        fields.put("aggregateId", this.aggregateId);
        fields.put("sequenceNumber", this.sequenceNumber);
        out.writeFields();
    }

    @Serial
    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        ObjectInputStream.GetField fields             = input.readFields();
        UUID                       readId             = (UUID) fields.get("id", null);
        Instant                    readOccurredAt     = (Instant) fields.get("occurredAt", null);
        String                     readAggregateId    = (String) fields.get("aggregateId", null);
        long                       readSequenceNumber = fields.get("sequenceNumber", UNASSIGNED_SEQUENCE_NUMBER);

        if (readId == null || readOccurredAt == null || readAggregateId == null) {
            throw new InvalidObjectException(
                    "AbstractDomainEvent payload is missing one of the required fields "
                            + "(id, occurredAt, aggregateId)");
        }

        if (readSequenceNumber < 0 && readSequenceNumber != UNASSIGNED_SEQUENCE_NUMBER) {
            throw new InvalidObjectException(
                    "AbstractDomainEvent.sequenceNumber must be non-negative or "
                            + UNASSIGNED_SEQUENCE_NUMBER
                            + " (sentinel); got "
                            + readSequenceNumber);
        }

        setFinalField("id", readId);
        setFinalField("occurredAt", readOccurredAt);
        setFinalField("aggregateId", readAggregateId);
        this.sequenceNumber = readSequenceNumber;
        this.headers        = Collections.emptyMap();
    }

    @SuppressWarnings("java:S3011") // Intentional serialization rehydration of final fields from
    // serialPersistentFields.
    private void setFinalField(String name, Object value) throws InvalidObjectException {
        try {
            java.lang.reflect.Field field = AbstractDomainEvent.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(this, value);
        } catch (ReflectiveOperationException e) {
            throw new InvalidObjectException("cannot rehydrate AbstractDomainEvent field " + name, e);
        }
    }
}
