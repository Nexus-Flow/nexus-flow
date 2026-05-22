package net.nexus_flow.core.outbox;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.jspecify.annotations.Nullable;

/**
 * drain helper that takes the events produced by an {@code AggregateRoot.drainEvents()} call and
 * appends them to an {@link OutboxStorage}.
 *
 * <p>This class is intentionally <strong>not wired into the command bus</strong>: appenders must
 * insert the call between {@code dispatchAndReturnResultBody} and the fan-out step so that the
 * aggregate persistence and the outbox append happen in the same transaction.
 *
 * <p>This helper only validates the row format and the {@link OutboxStorage} contract. Payload
 * encoding is delegated to the pluggable {@link OutboxPayloadCodec}. {@code payloadBytes} is set to
 * an empty array by default; integration modules will introduce custom codecs (Avro / JSON / Java)
 * without changing this signature.
 */
public final class OutboxAppender {

    private OutboxAppender() {
        // utility
    }

    /**
     * Appends every event from {@code events}, in iteration order, to {@code storage}. The {@link
     * ExecutionContext} active at {@code recordEvent(...)} time provides the trace / correlation /
     * causation / message ids that travel with each row.
     *
     * <p>This 4-argument overload preserves the legacy contract: {@code payloadBytes} stays empty.
     * Callers that need the payload encoded MUST use {@link #appendDrainedEvents(List,
     * ExecutionContext, OutboxStorage, Clock, OutboxPayloadCodec)}.
     *
     * @param events  the domain events drained from an aggregate's event stream; must not be {@code
     *     null}
     * @param ctx     the execution context carrying trace / correlation / causation / message ids; must
     *                not be {@code null}
     * @param storage the outbox to append to; must not be {@code null}
     * @param clock   clock for the {@code recordedAt} timestamp; must not be {@code null}
     * @throws OutboxDuplicateKeyException if any event in the batch has an {@link IdempotencyKey}
     *                                     that already exists in {@code storage}; events that came earlier in the iteration order
     *                                     have already been appended at that point (callers wrap this call in a database transaction)
     */
    public static void appendDrainedEvents(
            List<DomainEvent> events, ExecutionContext ctx, OutboxStorage storage, Clock clock) {
        appendDrainedEvents(events, ctx, storage, clock, null);
    }

    /**
     * Codec-aware variant. {@code codec} encodes each event into {@link OutboxRecord#payloadBytes()}
     * so the {@link OutboxWorker} can later rebuild the event and re-publish it online. A {@code
     * null} codec falls back to the legacy {@code new byte[0]} payload slot (contract).
     *
     * @param events  the domain events drained from an aggregate's event stream; must not be {@code
     *     null}
     * @param ctx     the execution context; must not be {@code null}
     * @param storage the outbox to append to; must not be {@code null}
     * @param clock   clock for the {@code recordedAt} timestamp; must not be {@code null}
     * @param codec   optional payload codec; {@code null} falls back to empty-payload contract
     * @throws OutboxDuplicateKeyException if any event has a duplicate idempotency key in storage
     */
    public static void appendDrainedEvents(
            List<DomainEvent> events,
            ExecutionContext ctx,
            OutboxStorage storage,
            Clock clock,
            @Nullable OutboxPayloadCodec codec) {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(clock, "clock");
        for (DomainEvent event : events) {
            storage.append(toRecord(event, ctx, clock, codec));
        }
    }

    /**
     * Converts a single domain event to an {@link OutboxRecord} without encoding the payload (legacy,
     * no-codec form).
     *
     * <p>Visible for tests and wire-up code. Prefer the codec-aware overload when a payload codec is
     * available.
     *
     * @param event the domain event to convert; must not be {@code null}
     * @param ctx   the execution context supplying trace / correlation / message ids; must not be
     *              {@code null}
     * @param clock clock for the {@code recordedAt} timestamp; must not be {@code null}
     * @return a new {@link OutboxRecord} with an empty {@code payloadBytes} array
     * @throws IllegalArgumentException if the event has not been recorded on an aggregate (no
     *                                  sequence number) or has a {@code null} aggregateId
     */
    public static OutboxRecord toRecord(DomainEvent event, ExecutionContext ctx, Clock clock) {
        return toRecord(event, ctx, clock, null);
    }

    /**
     * Codec-aware form. Encodes the event payload when {@code codec} is non-null.
     *
     * @param event the domain event to convert; must not be {@code null}
     * @param ctx   the execution context; must not be {@code null}
     * @param clock clock for the {@code recordedAt} timestamp; must not be {@code null}
     * @param codec optional payload codec; {@code null} produces an empty {@code payloadBytes}
     * @return a new {@link OutboxRecord} in {@link OutboxStatus#PENDING} status
     * @throws IllegalArgumentException if the event has not been recorded on an aggregate or has a
     *                                  {@code null} aggregateId
     * @throws OutboxCodecException     if the codec fails to encode the event
     */
    public static OutboxRecord toRecord(
            DomainEvent event, ExecutionContext ctx, Clock clock, @Nullable OutboxPayloadCodec codec) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(clock, "clock");
        // DomainEvent is sealed to permit only AbstractDomainEvent, so the cast is safe. The check
        // is retained because the sealed contract could relax in future and an unrecorded
        // AbstractDomainEvent has UNASSIGNED_SEQUENCE_NUMBER (-1) — the storage column is non-negative.
        if (!(event instanceof AbstractDomainEvent abs)) {
            throw new IllegalArgumentException(
                    "OutboxAppender requires AbstractDomainEvent, got "
                            + event.getClass().getName()
                            + " (id="
                            + event.getId()
                            + "). Custom DomainEvent subtypes must extend AbstractDomainEvent so the outbox row"
                            + " carries a stable sequence number for the idempotency key.");
        }
        long seq = abs.getSequenceNumber();
        if (seq < 0) {
            throw new IllegalArgumentException(
                    "sequenceNumber not assigned on event "
                            + event.getClass().getName()
                            + " (id="
                            + event.getId()
                            + "); must record the event on an aggregate before appending to the outbox.");
        }
        String aggregateId = event.getAggregateId();
        if (aggregateId == null) {
            throw new IllegalArgumentException(
                    "event " + event.getClass().getName() + " has a null aggregateId");
        }
        byte[] payload = codec == null ? new byte[0] : codec.encode(event);
        String codecId = codec == null ? null : codec.codecId();
        return new OutboxRecord(
                OutboxId.next(),
                IdempotencyKey.from(event),
                event.getClass().getName(),
                aggregateId,
                seq,
                ctx.traceId(),
                ctx.correlationId(),
                ctx.causationId(),
                ctx.messageId(),
                event.getClass(),
                payload,
                clock.instant(),
                OutboxStatus.PENDING,
                0,
                null,
                null,
                null,
                ctx.tenant(),
                codecId);
    }
}
