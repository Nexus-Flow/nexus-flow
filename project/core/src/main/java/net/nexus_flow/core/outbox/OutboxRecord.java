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
 * @param outboxId       stable surrogate primary key; monotonically ordered within the JVM (see {@link
 *                       OutboxId#next()})
 * @param idempotencyKey business-level deduplication handle derived from the event (typically
 *                       {@code aggregateId:sequenceNo}); used to detect duplicate appends and to support the
 *                       manual-replay path
 * @param aggregateType  fully-qualified class name (or logical type name) of the source aggregate;
 *                       never blank
 * @param aggregateId    business identifier of the aggregate instance that produced the event;
 *                       non-null but may be any string format chosen by the aggregate
 * @param sequenceNo     monotonically increasing position of this event within the aggregate's event
 *                       stream; must be {@code >= 0}
 * @param traceId        distributed tracing handle propagated from the originating command context
 * @param correlationId  correlation identifier for grouping related commands and events across
 *                       service boundaries
 * @param causationId    identifier of the command or event that directly caused this event
 * @param messageId      stable, globally unique message identity; the {@link OutboxWorker} carries this
 *                       forward to the inbox dedup marker when the row is replayed
 * @param payloadType    runtime class of the original event; used by the {@link OutboxPayloadCodec} as
 *                       a decode hint
 * @param payloadBytes   serialised event payload; empty array when no codec was supplied at append
 *                       time
 * @param recordedAt     wall-clock instant at which the event was appended to the outbox; immutable
 *                       across retries
 * @param status         current lifecycle status; see {@link OutboxStatus} for legal transitions
 * @param attempts       number of delivery attempts completed so far (including failed ones); incremented
 *                       on each {@code asPublished}, {@code asRetrying}, or {@code asFailedTerminal} transition; must
 *                       be {@code >= 0}
 * @param lastError      flattened stack trace of the most recent delivery failure, or {@code null} if
 *                       the row has never failed
 * @param lastAttemptAt  wall-clock time of the most recent delivery attempt, or {@code null} when
 *                       {@code attempts == 0}
 * @param nextRetryAt    earliest wall-clock time at which the row is eligible to be re-claimed; {@code
 *     null}          for a brand-new row or after a successful delivery; set by {@link
 *                       OutboxStorage#markFailed} when scheduling an exponential-backoff retry
 * @param tenantId       multi-tenant scope persisted from the original dispatch's {@link
 *                       net.nexus_flow.core.runtime.ExecutionContext#tenant()}; {@code null} for single-tenant or
 *                       system-level dispatches. Restored by {@link OutboxWorker#processOne(OutboxRecord)} so
 *                       per-tenant tags (metrics, tracing attributes) line up across the original dispatch and any
 *                       re-dispatch after restart
 * @param codecId        stable identity of the {@link OutboxPayloadCodec} that produced {@link
 *                       #payloadBytes()}; persisted so a multi-codec deployment (e.g. mid-migration JSON v1 + JSON
 *                       v2, or legacy Java-serialization + new Avro) can route the decode call to the matching codec
 *                       via {@link OutboxPayloadCodecRegistry}. {@code null} for rows written before this field
 *                       existed or by a codec-less append; such rows fall through to {@link OutboxConfig#codec()} on
 *                       decode (the primary single-codec path)
 */
public record OutboxRecord(
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

    public OutboxRecord {
        Objects.requireNonNull(outboxId, "outboxId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(aggregateType, "aggregateType");
        if (aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType must not be blank");
        }
        Objects.requireNonNull(aggregateId, "aggregateId");
        if (sequenceNo < 0) {
            throw new IllegalArgumentException("sequenceNo must be >= 0: " + sequenceNo);
        }
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(causationId, "causationId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(payloadType, "payloadType");
        Objects.requireNonNull(payloadBytes, "payloadBytes");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(status, "status");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be >= 0: " + attempts);
        }
        if (attempts == 0 && lastAttemptAt != null) {
            throw new IllegalArgumentException("lastAttemptAt must be null when attempts == 0");
        }
        // nextRetryAt is meaningful only for PENDING rows
        // post-failure. We do not forbid it on other states (storage
        // implementations may carry it forward) but we do forbid future
        // dates on terminal states by convention.
    }

    /**
     * Back-compat constructor preserving the pre-{@code codecId} field arity. Delegates with {@code
     * codecId = null} so existing callers (tests, legacy adapters) keep working without a sweep. New
     * production callers should use the canonical 19-arg constructor so the routing discriminator is
     * populated.
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
        this(
             outboxId,
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
             null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof OutboxRecord(OutboxId id, IdempotencyKey key, String type, String aggregateId1, long no, TraceId traceId1, CorrelationId correlationId1, CausationId causationId1, MessageId messageId1, Class<?> payloadType1, byte[] bytes, Instant at, OutboxStatus status1, int attempts1, String error, Instant attemptAt, Instant retryAt, TenantId tenantId1, String codecId1)))
            return false;
        return sequenceNo == no && attempts == attempts1 && Objects.equals(outboxId, id) && Objects.equals(
                                                                                                           idempotencyKey, key) && Objects
                                                                                                                   .equals(aggregateType,
                                                                                                                           type) && Objects
                                                                                                                                   .equals(aggregateId,
                                                                                                                                           aggregateId1) && Objects
                                                                                                                                                   .equals(
                                                                                                                                                           traceId,
                                                                                                                                                           traceId1) && Objects
                                                                                                                                                                   .equals(correlationId,
                                                                                                                                                                           correlationId1) && Objects
                                                                                                                                                                                   .equals(
                                                                                                                                                                                           causationId,
                                                                                                                                                                                           causationId1) && Objects
                                                                                                                                                                                                   .equals(
                                                                                                                                                                                                           messageId,
                                                                                                                                                                                                           messageId1) && Objects
                                                                                                                                                                                                                   .equals(payloadType,
                                                                                                                                                                                                                           payloadType1) && Arrays
                                                                                                                                                                                                                                   .equals(payloadBytes,
                                                                                                                                                                                                                                           bytes) && Objects
                                                                                                                                                                                                                                                   .equals(
                                                                                                                                                                                                                                                           recordedAt,
                                                                                                                                                                                                                                                           at) && Objects
                                                                                                                                                                                                                                                                   .equals(status,
                                                                                                                                                                                                                                                                           status1) && Objects
                                                                                                                                                                                                                                                                                   .equals(lastError,
                                                                                                                                                                                                                                                                                           error) && Objects
                                                                                                                                                                                                                                                                                                   .equals(
                                                                                                                                                                                                                                                                                                           lastAttemptAt,
                                                                                                                                                                                                                                                                                                           attemptAt) && Objects
                                                                                                                                                                                                                                                                                                                   .equals(nextRetryAt,
                                                                                                                                                                                                                                                                                                                           retryAt) && Objects
                                                                                                                                                                                                                                                                                                                                   .equals(tenantId,
                                                                                                                                                                                                                                                                                                                                           tenantId1) && Objects
                                                                                                                                                                                                                                                                                                                                                   .equals(
                                                                                                                                                                                                                                                                                                                                                           codecId,
                                                                                                                                                                                                                                                                                                                                                           codecId1);
    }

    @Override
    public int hashCode() {
        int result =
                Objects.hash(
                             outboxId,
                             idempotencyKey,
                             aggregateType,
                             aggregateId,
                             sequenceNo,
                             traceId,
                             correlationId,
                             causationId,
                             messageId,
                             payloadType,
                             recordedAt,
                             status,
                             attempts,
                             lastError,
                             lastAttemptAt,
                             nextRetryAt,
                             tenantId,
                             codecId);
        result = 31 * result + Arrays.hashCode(payloadBytes);
        return result;
    }

    @Override
    public String toString() {
        return "OutboxRecord{"
                + "outboxId="
                + outboxId
                + ", idempotencyKey="
                + idempotencyKey
                + ", aggregateType='"
                + aggregateType
                + '\''
                + ", aggregateId='"
                + aggregateId
                + '\''
                + ", sequenceNo="
                + sequenceNo
                + ", traceId="
                + traceId
                + ", correlationId="
                + correlationId
                + ", causationId="
                + causationId
                + ", messageId="
                + messageId
                + ", payloadType="
                + payloadType
                + ", payloadBytes="
                + Arrays.toString(payloadBytes)
                + ", recordedAt="
                + recordedAt
                + ", status="
                + status
                + ", attempts="
                + attempts
                + ", lastError='"
                + lastError
                + '\''
                + ", lastAttemptAt="
                + lastAttemptAt
                + ", nextRetryAt="
                + nextRetryAt
                + ", tenantId="
                + tenantId
                + ", codecId="
                + codecId
                + '}';
    }

    /**
     * Returns a fresh {@link OutboxStatus#PENDING} row with the same identity, preserving all fields
     * except {@code status} and {@code nextRetryAt}.
     *
     * <p>Intended for storage implementations that need to reset a row to a retryable state while
     * keeping all diagnostic information intact.
     *
     * @param nextRetryAt earliest instant at which the row may be re-claimed; may be {@code null} to
     *                    make the row immediately eligible
     * @return a new {@code OutboxRecord} in {@code PENDING} status with the supplied {@code
     *     nextRetryAt}
     */
    public OutboxRecord asPending(Instant nextRetryAt) {
        return new OutboxRecord(
                outboxId,
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
                OutboxStatus.PENDING,
                attempts,
                lastError,
                lastAttemptAt,
                nextRetryAt,
                tenantId,
                codecId);
    }

    /**
     * Returns a copy of this record with only the {@code status} changed.
     *
     * <p>All other fields — including {@code attempts}, {@code lastError}, and {@code nextRetryAt} —
     * are preserved unchanged. Prefer the semantic helpers ({@link #asPublished}, {@link
     * #asRetrying}, {@link #asFailedTerminal}) for standard lifecycle transitions; this method is
     * provided for storage implementations that need to flip the status without touching other
     * fields.
     *
     * @param newStatus the target status; must not be {@code null}
     * @return a new {@code OutboxRecord} with the given status and all other fields unchanged
     * @throws NullPointerException if {@code newStatus} is {@code null}
     */
    public OutboxRecord withStatus(OutboxStatus newStatus) {
        Objects.requireNonNull(newStatus, "newStatus");
        return new OutboxRecord(
                outboxId,
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
                newStatus,
                attempts,
                lastError,
                lastAttemptAt,
                nextRetryAt,
                tenantId,
                codecId);
    }

    /**
     * Returns the post-success row: status {@link OutboxStatus#PUBLISHED}, {@code attempts}
     * incremented by one, {@code lastError} cleared, and {@code nextRetryAt} cleared.
     *
     * @param lastAttemptAt wall-clock time of the successful delivery attempt; stored for auditing
     * @return a new {@code OutboxRecord} representing the successfully published state
     */
    public OutboxRecord asPublished(Instant lastAttemptAt) {
        return new OutboxRecord(
                outboxId,
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
                OutboxStatus.PUBLISHED,
                attempts + 1,
                null,
                lastAttemptAt,
                null,
                tenantId,
                codecId);
    }

    /**
     * Returns the post-retryable-failure row: status {@link OutboxStatus#PENDING}, {@code attempts}
     * incremented by one, {@code lastError} set to the flattened stack trace, and {@code nextRetryAt}
     * set to the next eligible retry time.
     *
     * <p>The row will be re-claimed by {@link OutboxStorage#claimBatch} once wall-clock time reaches
     * {@code nextRetryAt}.
     *
     * @param flattenedError stringified stack trace of the failure cause; stored for diagnostics
     * @param lastAttemptAt  wall-clock time of the failed delivery attempt
     * @param nextRetryAt    earliest instant at which the row may be re-claimed
     * @return a new {@code OutboxRecord} in {@code PENDING} status scheduled for retry
     */
    public OutboxRecord asRetrying(
            String flattenedError, Instant lastAttemptAt, Instant nextRetryAt) {
        return new OutboxRecord(
                outboxId,
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
                OutboxStatus.PENDING,
                attempts + 1,
                flattenedError,
                lastAttemptAt,
                nextRetryAt,
                tenantId,
                codecId);
    }

    /**
     * Returns the post-fatal-failure row: status {@link OutboxStatus#FAILED_TERMINAL}, {@code
     * attempts} incremented by one, {@code lastError} set, and {@code nextRetryAt} cleared.
     *
     * <p>{@code FAILED_TERMINAL} is a sink state: the row will never appear in a subsequent {@link
     * OutboxStorage#claimBatch} call. Only a manual re-append with the same {@link IdempotencyKey}
     * (the replay path) can resurrect it.
     *
     * @param flattenedError stringified stack trace of the fatal failure cause
     * @param lastAttemptAt  wall-clock time of the final delivery attempt
     * @return a new {@code OutboxRecord} in {@code FAILED_TERMINAL} status
     */
    public OutboxRecord asFailedTerminal(String flattenedError, Instant lastAttemptAt) {
        return new OutboxRecord(
                outboxId,
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
                OutboxStatus.FAILED_TERMINAL,
                attempts + 1,
                flattenedError,
                lastAttemptAt,
                null,
                tenantId,
                codecId);
    }
}
