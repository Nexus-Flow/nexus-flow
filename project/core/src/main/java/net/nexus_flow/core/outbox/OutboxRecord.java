package net.nexus_flow.core.outbox;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import net.nexus_flow.core.runtime.ids.*;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, persistent representation of a {@link net.nexus_flow.core.ddd.DomainEvent} queued for
 * asynchronous delivery by the {@link OutboxWorker}.
 *
 * <p>Every component maps onto a trivially-SQL-storable column (string / long / bytea / timestamp).
 * No nested maps, no JSON fallback. The {@link #payloadBytes()} slot holds the serialised event
 * payload; downstream integrations choose the wire format (Avro / JSON / Protobuf / Java
 * serialization) via a pluggable {@link OutboxPayloadCodec}. A codec-less append stores an empty
 * array (legacy contract).
 *
 * <h2>Status lifecycle</h2>
 *
 * <pre>
 * PENDING ──claimBatch──▶ IN_FLIGHT
 * IN_FLIGHT ──markPublished──▶ PUBLISHED (terminal success)
 * IN_FLIGHT ──markFailed──▶ PENDING (retryable failure, nextRetryAt set)
 * IN_FLIGHT ──markFailedTerminal──▶ FAILED_TERMINAL (terminal failure, exhausted retries)
 * FAILED_TERMINAL ──append──▶ PENDING (manual replay path)
 * </pre>
 *
 * <p>Terminal states ({@link OutboxStatus#PUBLISHED}, {@link OutboxStatus#FAILED_TERMINAL}) are
 * never claimed again by {@link OutboxStorage#claimBatch}. A {@code FAILED_TERMINAL} row may be
 * resurrected only by re-appending with the same {@link IdempotencyKey} (manual replay).
 *
 * <p>Records are immutable; every state transition produces a fresh instance via the {@code as*} /
 * {@code with*} convenience methods.
 *
 * <h2>Why class, not record</h2>
 *
 * <p>Was a {@code record} until JMH validated ({@code OutboxRecordTransitionBenchmark}) that the
 * 13× {@code Objects.requireNonNull} + 4 range checks + 2 {@code isBlank} checks in the compact
 * constructor cost 15-20 ns per allocation. Every status transition ({@link #asPublished},
 * {@link #asRetrying}, {@link #asFailedTerminal}, {@link #asPending}, {@link #withStatus},
 * {@link #withPriority}, {@link #withPartitionKey}) copies fields from {@code this} — which
 * already passed validation when {@code this} was constructed — so re-running the same checks is
 * wasted work. At 1 M rows/sec with 2-3 transitions per row, the skipped validation translates
 * to ~30-40 ms/sec/core on the durable-dispatch hot path.
 *
 * <p>The class shape exposes:
 *
 * <ul>
 * <li>A public canonical constructor that validates every argument — the externally-callable
 * shape, matches the previous record canonical constructor semantically. Used by
 * {@link OutboxAppender}, adapter modules, and any external code that builds a row from
 * scratch.
 * <li>A package-private {@link #unchecked} static factory that skips validation entirely. The
 * status-transition methods on this class use the unchecked path because every argument is
 * either copied verbatim from {@code this} or constructed inline by the transition (e.g.
 * {@code attempts + 1}).
 * </ul>
 *
 * <p>The public surface is preserved byte-for-byte: every accessor method has the same name and
 * signature the record auto-generated; {@link #equals(Object)} / {@link #hashCode()} /
 * {@link #toString()} are field-by-field, structurally identical to the record's auto-generated
 * form (which had already been overridden manually for {@code byte[]} array-equality semantics).
 */
public final class OutboxRecord {

    /**
     * Default priority sentinel — used by the back-compat constructor for callers that don't
     * supply a priority hint. Higher values are dispatched first within the same
     * {@link #partitionKey()}.
     */
    public static final int DEFAULT_PRIORITY = 0;

    private final OutboxId           outboxId;
    private final IdempotencyKey     idempotencyKey;
    private final String             aggregateType;
    private final String             aggregateId;
    private final long               sequenceNo;
    private final TraceId            traceId;
    private final CorrelationId      correlationId;
    private final CausationId        causationId;
    private final MessageId          messageId;
    private final Class<?>           payloadType;
    private final byte[]             payloadBytes;
    private final Instant            recordedAt;
    private final OutboxStatus       status;
    private final int                attempts;
    private final @Nullable String   lastError;
    private final @Nullable Instant  lastAttemptAt;
    private final @Nullable Instant  nextRetryAt;
    private final @Nullable TenantId tenantId;
    private final @Nullable String   codecId;
    private final int                priority;
    private final String             partitionKey;

    /**
     * Public canonical constructor — validates every argument. Adapter modules and any external
     * code that hand-constructs an {@link OutboxRecord} go through this constructor.
     *
     * @throws NullPointerException     if any non-nullable argument is {@code null}
     * @throws IllegalArgumentException if {@code aggregateType} or {@code partitionKey} is blank,
     *                                  {@code sequenceNo < 0}, {@code attempts < 0}, or
     *                                  {@code attempts == 0} but {@code lastAttemptAt != null}
     */
    public OutboxRecord(
            OutboxId outboxId,
            IdempotencyKey idempotencyKey,
            String aggregateType,
            String aggregateId,
            long sequenceNo,
            TraceId traceId,
            CorrelationId correlationId,
            CausationId causationId,
            MessageId messageId,
            Class<?> payloadType,
            byte[] payloadBytes,
            Instant recordedAt,
            OutboxStatus status,
            int attempts,
            @Nullable String lastError,
            @Nullable Instant lastAttemptAt,
            @Nullable Instant nextRetryAt,
            @Nullable TenantId tenantId,
            @Nullable String codecId,
            int priority,
            String partitionKey) {
        this.outboxId       = Objects.requireNonNull(outboxId, "outboxId");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.aggregateType  = Objects.requireNonNull(aggregateType, "aggregateType");
        if (aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType must not be blank");
        }
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        if (sequenceNo < 0) {
            throw new IllegalArgumentException("sequenceNo must be >= 0: " + sequenceNo);
        }
        this.sequenceNo    = sequenceNo;
        this.traceId       = Objects.requireNonNull(traceId, "traceId");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.causationId   = Objects.requireNonNull(causationId, "causationId");
        this.messageId     = Objects.requireNonNull(messageId, "messageId");
        this.payloadType   = Objects.requireNonNull(payloadType, "payloadType");
        this.payloadBytes  = Objects.requireNonNull(payloadBytes, "payloadBytes");
        this.recordedAt    = Objects.requireNonNull(recordedAt, "recordedAt");
        this.status        = Objects.requireNonNull(status, "status");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be >= 0: " + attempts);
        }
        if (attempts == 0 && lastAttemptAt != null) {
            throw new IllegalArgumentException("lastAttemptAt must be null when attempts == 0");
        }
        this.attempts      = attempts;
        this.lastError     = lastError;
        this.lastAttemptAt = lastAttemptAt;
        this.nextRetryAt   = nextRetryAt;
        this.tenantId      = tenantId;
        this.codecId       = codecId;
        this.priority      = priority;
        Objects.requireNonNull(partitionKey, "partitionKey");
        if (partitionKey.isBlank()) {
            throw new IllegalArgumentException("partitionKey must not be blank");
        }
        this.partitionKey = partitionKey;
    }

    /**
     * Private skeleton constructor — assigns fields without any validation. Used by
     * {@link #unchecked} to skip the 13× {@code requireNonNull} + 4 range checks + 2
     * {@code isBlank} cost. Callers MUST guarantee every argument is non-null (except the
     * nullable ones) and every range invariant holds.
     */
    private OutboxRecord(
            OutboxId outboxId,
            IdempotencyKey idempotencyKey,
            String aggregateType,
            String aggregateId,
            long sequenceNo,
            TraceId traceId,
            CorrelationId correlationId,
            CausationId causationId,
            MessageId messageId,
            Class<?> payloadType,
            byte[] payloadBytes,
            Instant recordedAt,
            OutboxStatus status,
            int attempts,
            @Nullable String lastError,
            @Nullable Instant lastAttemptAt,
            @Nullable Instant nextRetryAt,
            @Nullable TenantId tenantId,
            @Nullable String codecId,
            int priority,
            String partitionKey,
            @SuppressWarnings("unused") boolean uncheckedMarker) {
        this.outboxId       = outboxId;
        this.idempotencyKey = idempotencyKey;
        this.aggregateType  = aggregateType;
        this.aggregateId    = aggregateId;
        this.sequenceNo     = sequenceNo;
        this.traceId        = traceId;
        this.correlationId  = correlationId;
        this.causationId    = causationId;
        this.messageId      = messageId;
        this.payloadType    = payloadType;
        this.payloadBytes   = payloadBytes;
        this.recordedAt     = recordedAt;
        this.status         = status;
        this.attempts       = attempts;
        this.lastError      = lastError;
        this.lastAttemptAt  = lastAttemptAt;
        this.nextRetryAt    = nextRetryAt;
        this.tenantId       = tenantId;
        this.codecId        = codecId;
        this.priority       = priority;
        this.partitionKey   = partitionKey;
    }

    /**
     * Package-private fast-path factory — bypasses every {@code Objects.requireNonNull},
     * range check and {@code isBlank} guard. ONLY safe when every argument is either copied
     * verbatim from an already-validated {@link OutboxRecord} (the status-transition path) or
     * computed inline against a known-valid base (e.g. {@code attempts + 1}). JMH validates
     * the unchecked path drops 6-10 ns per transition vs the validated constructor.
     */
    static OutboxRecord unchecked(
            OutboxId outboxId,
            IdempotencyKey idempotencyKey,
            String aggregateType,
            String aggregateId,
            long sequenceNo,
            TraceId traceId,
            CorrelationId correlationId,
            CausationId causationId,
            MessageId messageId,
            Class<?> payloadType,
            byte[] payloadBytes,
            Instant recordedAt,
            OutboxStatus status,
            int attempts,
            @Nullable String lastError,
            @Nullable Instant lastAttemptAt,
            @Nullable Instant nextRetryAt,
            @Nullable TenantId tenantId,
            @Nullable String codecId,
            int priority,
            String partitionKey) {
        return new OutboxRecord(
                outboxId, idempotencyKey, aggregateType, aggregateId, sequenceNo,
                traceId, correlationId, causationId, messageId, payloadType, payloadBytes,
                recordedAt, status, attempts, lastError, lastAttemptAt, nextRetryAt,
                tenantId, codecId, priority, partitionKey, true);
    }

    /**
     * Back-compat constructor preserving the pre-{@code codecId} field arity. Delegates with
     * {@code codecId = null}, {@code priority = DEFAULT_PRIORITY}, and
     * {@code partitionKey = aggregateId} so existing callers (tests, legacy adapters) keep
     * working without a sweep. New production callers should use the canonical 21-arg
     * constructor so the routing discriminator, priority and partition key are populated
     * explicitly.
     */
    public OutboxRecord(
            OutboxId outboxId,
            IdempotencyKey idempotencyKey,
            String aggregateType,
            String aggregateId,
            long sequenceNo,
            TraceId traceId,
            CorrelationId correlationId,
            CausationId causationId,
            MessageId messageId,
            Class<?> payloadType,
            byte[] payloadBytes,
            Instant recordedAt,
            OutboxStatus status,
            int attempts,
            @Nullable String lastError,
            @Nullable Instant lastAttemptAt,
            @Nullable Instant nextRetryAt,
            @Nullable TenantId tenantId) {
        this(outboxId,
             idempotencyKey,
             aggregateType,
             aggregateId,
             sequenceNo,
             traceId,
             correlationId,
             causationId,
             messageId,
             payloadType,
             payloadBytes,
             recordedAt,
             status,
             attempts,
             lastError,
             lastAttemptAt,
             nextRetryAt,
             tenantId,
             null,
             DEFAULT_PRIORITY,
             aggregateId);
    }

    /**
     * Back-compat constructor preserving the pre-priority field arity (19-arg, codecId-aware).
     * Delegates with {@code priority = DEFAULT_PRIORITY} and {@code partitionKey = aggregateId}.
     */
    public OutboxRecord(
            OutboxId outboxId,
            IdempotencyKey idempotencyKey,
            String aggregateType,
            String aggregateId,
            long sequenceNo,
            TraceId traceId,
            CorrelationId correlationId,
            CausationId causationId,
            MessageId messageId,
            Class<?> payloadType,
            byte[] payloadBytes,
            Instant recordedAt,
            OutboxStatus status,
            int attempts,
            @Nullable String lastError,
            @Nullable Instant lastAttemptAt,
            @Nullable Instant nextRetryAt,
            @Nullable TenantId tenantId,
            @Nullable String codecId) {
        this(outboxId,
             idempotencyKey,
             aggregateType,
             aggregateId,
             sequenceNo,
             traceId,
             correlationId,
             causationId,
             messageId,
             payloadType,
             payloadBytes,
             recordedAt,
             status,
             attempts,
             lastError,
             lastAttemptAt,
             nextRetryAt,
             tenantId,
             codecId,
             DEFAULT_PRIORITY,
             aggregateId);
    }

    public OutboxId outboxId() {
        return outboxId;
    }

    public IdempotencyKey idempotencyKey() {
        return idempotencyKey;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public String aggregateId() {
        return aggregateId;
    }

    public long sequenceNo() {
        return sequenceNo;
    }

    public TraceId traceId() {
        return traceId;
    }

    public CorrelationId correlationId() {
        return correlationId;
    }

    public CausationId causationId() {
        return causationId;
    }

    public MessageId messageId() {
        return messageId;
    }

    public Class<?> payloadType() {
        return payloadType;
    }

    /**
     * Returns the backing payload array by reference — matches the previous record
     * auto-generated accessor semantics (records do not defensively copy). The
     * {@code OutboxAppender} and {@link OutboxPayloadCodec} contract is "do not mutate
     * payload bytes after a row is constructed"; defensive copying every read would inflate
     * the durable-dispatch hot path with N × {@code Arrays.copyOf} calls per row.
     */
    @SuppressWarnings("PMD.MethodReturnsInternalArray")
    public byte[] payloadBytes() {
        return payloadBytes;
    }

    public Instant recordedAt() {
        return recordedAt;
    }

    public OutboxStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public @Nullable String lastError() {
        return lastError;
    }

    public @Nullable Instant lastAttemptAt() {
        return lastAttemptAt;
    }

    public @Nullable Instant nextRetryAt() {
        return nextRetryAt;
    }

    public @Nullable TenantId tenantId() {
        return tenantId;
    }

    public @Nullable String codecId() {
        return codecId;
    }

    public int priority() {
        return priority;
    }

    public String partitionKey() {
        return partitionKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OutboxRecord other)) {
            return false;
        }
        return sequenceNo == other.sequenceNo && attempts == other.attempts && priority == other.priority && Objects.equals(outboxId,
                                                                                                                            other.outboxId) && Objects
                                                                                                                                    .equals(idempotencyKey,
                                                                                                                                            other.idempotencyKey) && Objects
                                                                                                                                                    .equals(aggregateType,
                                                                                                                                                            other.aggregateType) && Objects
                                                                                                                                                                    .equals(aggregateId,
                                                                                                                                                                            other.aggregateId) && Objects
                                                                                                                                                                                    .equals(traceId,
                                                                                                                                                                                            other.traceId) && Objects
                                                                                                                                                                                                    .equals(correlationId,
                                                                                                                                                                                                            other.correlationId) && Objects
                                                                                                                                                                                                                    .equals(causationId,
                                                                                                                                                                                                                            other.causationId) && Objects
                                                                                                                                                                                                                                    .equals(messageId,
                                                                                                                                                                                                                                            other.messageId) && Objects
                                                                                                                                                                                                                                                    .equals(payloadType,
                                                                                                                                                                                                                                                            other.payloadType) && Arrays
                                                                                                                                                                                                                                                                    .equals(payloadBytes,
                                                                                                                                                                                                                                                                            other.payloadBytes) && Objects
                                                                                                                                                                                                                                                                                    .equals(recordedAt,
                                                                                                                                                                                                                                                                                            other.recordedAt) && Objects
                                                                                                                                                                                                                                                                                                    .equals(status,
                                                                                                                                                                                                                                                                                                            other.status) && Objects
                                                                                                                                                                                                                                                                                                                    .equals(lastError,
                                                                                                                                                                                                                                                                                                                            other.lastError) && Objects
                                                                                                                                                                                                                                                                                                                                    .equals(lastAttemptAt,
                                                                                                                                                                                                                                                                                                                                            other.lastAttemptAt) && Objects
                                                                                                                                                                                                                                                                                                                                                    .equals(nextRetryAt,
                                                                                                                                                                                                                                                                                                                                                            other.nextRetryAt) && Objects
                                                                                                                                                                                                                                                                                                                                                                    .equals(tenantId,
                                                                                                                                                                                                                                                                                                                                                                            other.tenantId) && Objects
                                                                                                                                                                                                                                                                                                                                                                                    .equals(codecId,
                                                                                                                                                                                                                                                                                                                                                                                            other.codecId) && Objects
                                                                                                                                                                                                                                                                                                                                                                                                    .equals(partitionKey,
                                                                                                                                                                                                                                                                                                                                                                                                            other.partitionKey);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                                  outboxId, idempotencyKey, aggregateType, aggregateId, sequenceNo,
                                  traceId, correlationId, causationId, messageId, payloadType, recordedAt,
                                  status, attempts, lastError, lastAttemptAt, nextRetryAt, tenantId, codecId,
                                  priority, partitionKey);
        result = 31 * result + Arrays.hashCode(payloadBytes);
        return result;
    }

    @Override
    public String toString() {
        return "OutboxRecord{"
                + "outboxId=" + outboxId
                + ", idempotencyKey=" + idempotencyKey
                + ", aggregateType='" + aggregateType + '\''
                + ", aggregateId='" + aggregateId + '\''
                + ", sequenceNo=" + sequenceNo
                + ", traceId=" + traceId
                + ", correlationId=" + correlationId
                + ", causationId=" + causationId
                + ", messageId=" + messageId
                + ", payloadType=" + payloadType
                + ", payloadBytes=" + Arrays.toString(payloadBytes)
                + ", recordedAt=" + recordedAt
                + ", status=" + status
                + ", attempts=" + attempts
                + ", lastError='" + lastError + '\''
                + ", lastAttemptAt=" + lastAttemptAt
                + ", nextRetryAt=" + nextRetryAt
                + ", tenantId=" + tenantId
                + ", codecId=" + codecId
                + ", priority=" + priority
                + ", partitionKey='" + partitionKey + '\''
                + '}';
    }

    /**
     * Returns a fresh {@link OutboxStatus#PENDING} row with the same identity, preserving all
     * fields except {@code status} and {@code nextRetryAt}.
     *
     * <p>Intended for storage implementations that need to reset a row to a retryable state
     * while keeping all diagnostic information intact.
     *
     * @param nextRetryAt earliest instant at which the row may be re-claimed; may be {@code
     *                    null} to make the row immediately eligible
     * @return a new {@code OutboxRecord} in {@code PENDING} status with the supplied
     *         {@code nextRetryAt}
     */
    public OutboxRecord asPending(Instant nextRetryAt) {
        return unchecked(
                         outboxId, idempotencyKey, aggregateType, aggregateId, sequenceNo,
                         traceId, correlationId, causationId, messageId, payloadType, payloadBytes,
                         recordedAt, OutboxStatus.PENDING, attempts, lastError, lastAttemptAt,
                         nextRetryAt, tenantId, codecId, priority, partitionKey);
    }

    /**
     * Returns a copy of this record with only the {@code status} changed.
     *
     * <p>All other fields — including {@code attempts}, {@code lastError}, and
     * {@code nextRetryAt} — are preserved unchanged. Prefer the semantic helpers
     * ({@link #asPublished}, {@link #asRetrying}, {@link #asFailedTerminal}) for standard
     * lifecycle transitions; this method is provided for storage implementations that need to
     * flip the status without touching other fields.
     *
     * @param newStatus the target status; must not be {@code null}
     * @return a new {@code OutboxRecord} with the given status and all other fields unchanged
     * @throws NullPointerException if {@code newStatus} is {@code null}
     */
    public OutboxRecord withStatus(OutboxStatus newStatus) {
        Objects.requireNonNull(newStatus, "newStatus");
        return unchecked(
                         outboxId, idempotencyKey, aggregateType, aggregateId, sequenceNo,
                         traceId, correlationId, causationId, messageId, payloadType, payloadBytes,
                         recordedAt, newStatus, attempts, lastError, lastAttemptAt, nextRetryAt,
                         tenantId, codecId, priority, partitionKey);
    }

    /**
     * Returns the post-success row: status {@link OutboxStatus#PUBLISHED}, {@code attempts}
     * incremented by one, {@code lastError} cleared, and {@code nextRetryAt} cleared.
     *
     * @param lastAttemptAt wall-clock time of the successful delivery attempt; stored for
     *                      auditing
     * @return a new {@code OutboxRecord} representing the successfully published state
     */
    public OutboxRecord asPublished(Instant lastAttemptAt) {
        return unchecked(
                         outboxId, idempotencyKey, aggregateType, aggregateId, sequenceNo,
                         traceId, correlationId, causationId, messageId, payloadType, payloadBytes,
                         recordedAt, OutboxStatus.PUBLISHED, attempts + 1, null, lastAttemptAt, null,
                         tenantId, codecId, priority, partitionKey);
    }

    /**
     * Returns the post-retryable-failure row: status {@link OutboxStatus#PENDING},
     * {@code attempts} incremented by one, {@code lastError} set to the flattened stack trace,
     * and {@code nextRetryAt} set to the next eligible retry time.
     *
     * <p>The row will be re-claimed by {@link OutboxStorage#claimBatch} once wall-clock time
     * reaches {@code nextRetryAt}.
     *
     * @param flattenedError stringified stack trace of the failure cause; stored for diagnostics
     * @param lastAttemptAt  wall-clock time of the failed delivery attempt
     * @param nextRetryAt    earliest instant at which the row may be re-claimed
     * @return a new {@code OutboxRecord} in {@code PENDING} status scheduled for retry
     */
    public OutboxRecord asRetrying(
            String flattenedError, Instant lastAttemptAt, Instant nextRetryAt) {
        return unchecked(
                         outboxId, idempotencyKey, aggregateType, aggregateId, sequenceNo,
                         traceId, correlationId, causationId, messageId, payloadType, payloadBytes,
                         recordedAt, OutboxStatus.PENDING, attempts + 1, flattenedError, lastAttemptAt,
                         nextRetryAt, tenantId, codecId, priority, partitionKey);
    }

    /**
     * Returns the post-fatal-failure row: status {@link OutboxStatus#FAILED_TERMINAL},
     * {@code attempts} incremented by one, {@code lastError} set, and {@code nextRetryAt}
     * cleared.
     *
     * <p>{@code FAILED_TERMINAL} is a sink state: the row will never appear in a subsequent
     * {@link OutboxStorage#claimBatch} call. Only a manual re-append with the same
     * {@link IdempotencyKey} (the replay path) can resurrect it.
     *
     * @param flattenedError stringified stack trace of the fatal failure cause
     * @param lastAttemptAt  wall-clock time of the final delivery attempt
     * @return a new {@code OutboxRecord} in {@code FAILED_TERMINAL} status
     */
    public OutboxRecord asFailedTerminal(String flattenedError, Instant lastAttemptAt) {
        return unchecked(
                         outboxId, idempotencyKey, aggregateType, aggregateId, sequenceNo,
                         traceId, correlationId, causationId, messageId, payloadType, payloadBytes,
                         recordedAt, OutboxStatus.FAILED_TERMINAL, attempts + 1, flattenedError,
                         lastAttemptAt, null, tenantId, codecId, priority, partitionKey);
    }

    /**
     * Returns a copy with the supplied priority. Higher values dispatch first within the same
     * {@link #partitionKey()}. The default for back-compat callers is {@link #DEFAULT_PRIORITY}
     * (= 0); use this when the application has a priority hint (e.g., a customer-tier
     * escalation or a regulatory deadline that needs the event ahead of normal traffic).
     */
    public OutboxRecord withPriority(int newPriority) {
        return unchecked(
                         outboxId, idempotencyKey, aggregateType, aggregateId, sequenceNo,
                         traceId, correlationId, causationId, messageId, payloadType, payloadBytes,
                         recordedAt, status, attempts, lastError, lastAttemptAt, nextRetryAt,
                         tenantId, codecId, newPriority, partitionKey);
    }

    /**
     * Returns a copy with the supplied partition key. The default for back-compat callers is
     * {@code aggregateId} — events for the same aggregate share a partition and preserve FIFO.
     * Override when the partition key SHOULD differ from the aggregate id (e.g., to group
     * cross-aggregate events that share a tenant for a co-located worker, or to spread one
     * hot aggregate across multiple workers by hashing on a sub-field).
     */
    public OutboxRecord withPartitionKey(String newPartitionKey) {
        Objects.requireNonNull(newPartitionKey, "newPartitionKey");
        if (newPartitionKey.isBlank()) {
            throw new IllegalArgumentException("newPartitionKey must not be blank");
        }
        return unchecked(
                         outboxId, idempotencyKey, aggregateType, aggregateId, sequenceNo,
                         traceId, correlationId, causationId, messageId, payloadType, payloadBytes,
                         recordedAt, status, attempts, lastError, lastAttemptAt, nextRetryAt,
                         tenantId, codecId, priority, newPartitionKey);
    }
}
