package net.nexus_flow.core.outbox;

import java.time.Clock;
import java.util.Objects;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import net.nexus_flow.core.runtime.result.FlowDeadlineExceededException;
import org.jspecify.annotations.Nullable;

/**
 * durable async dispatch entry point for the transactional outbox pattern.
 *
 * <p>Bridges the {@link net.nexus_flow.core.runtime.ExecutionMode#asynchronousDurable()
 * AsynchronousDurable} mode to the outbox: instead of invoking a handler on the caller thread, the
 * message is appended to the {@link OutboxStorage} and a {@link DispatchResult.Accepted} carrying
 * the {@link net.nexus_flow.core.runtime.ids.MessageId} of the persisted row is returned. The
 * actual delivery happens later, asynchronously, when the {@link OutboxWorker} drains the row.
 * At-least-once delivery is guaranteed through re-drain on worker restart; idempotency is enforced
 * by inbox deduplication when configured.
 *
 * <p><strong>Cancellation / deadline contract:</strong> If {@code ctx} is already canceled or
 * expired the message is NOT appended and a {@link DispatchResult.Failure} is returned with the
 * matching runtime exception. This guarantees the durable path cannot leak messages past a canceled
 * flow.
 *
 * <p>This is a stateless utility — every call is independent. Wiring a {@code DurableDispatch} into
 * the runtime's {@code CommandHandlerExecutor} so handlers declaring {@code AsynchronousDurable}
 * are auto-routed here is left as a framework refinement; callers can invoke it explicitly through
 * this static method.
 */
public final class DurableDispatch {

    private DurableDispatch() {
        // static utility
    }

    /**
     * Append {@code event} to {@code storage} and return a {@link DispatchResult.Accepted} carrying
     * the message id.
     *
     * <p>Pre-conditions checked, in order:
     *
     * <ol>
     * <li>None of the arguments is {@code null}.
     * <li>{@code ctx} is not canceled and has not exceeded its deadline — otherwise a {@link
     * DispatchResult.Failure} wrapping the matching runtime exception is returned and {@code
     *       storage} is left untouched.
     * </ol>
     *
     * <p>On a successful append the returned {@link DispatchResult.Accepted#messageId()} is exactly
     * the {@link ExecutionContext#messageId()} of {@code ctx} — the same identity that lands in
     * {@link OutboxRecord#messageId()} and that the {@link OutboxWorker} carries forward to the inbox
     * dedupe marker when the message is replayed.
     *
     * @param event   domain event to durably queue; never {@code null}
     * @param ctx     execution context propagated through the dispatch; supplies trace / correlation /
     *                causation / message ids and the cancellation / deadline tokens
     * @param storage outbox storage to append to
     * @param clock   clock used for the {@code createdAt} timestamp
     * @param codec   optional payload codec; {@code null} falls back to the empty-payload contract
     * @return {@link DispatchResult.Accepted} on success; {@link DispatchResult.Failure} carrying a
     *         {@link FlowCancellationException} or {@link FlowDeadlineExceededException} if {@code ctx}
     *         was already canceled / expired
     */
    public static DispatchResult<Void> acceptAndAppend(
            DomainEvent event,
            ExecutionContext ctx,
            OutboxStorage storage,
            Clock clock,
            @Nullable OutboxPayloadCodec codec) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(clock, "clock");

        // Check cancellation before appending: the outbox is durable, so once a row lands the
        // worker will drain it regardless of who asked for it. Honoring cancellation HERE is the
        // only safe place to prevent messages from leaking past a canceled flow.
        try {
            ctx.throwIfCancelledOrExpired();
        } catch (FlowCancellationException | FlowDeadlineExceededException ce) {
            return DispatchResult.failure(ce);
        }

        OutboxRecord outboxRecord = OutboxAppender.toRecord(event, ctx, clock, codec);
        try {
            storage.append(outboxRecord);
        } catch (RuntimeException re) {
            // Append failed (typically a duplicate-key collision because the same message id
            // was already persisted). Propagate the exception verbatim through DispatchResult.Failure.
            return DispatchResult.failure(re);
        }
        return DispatchResult.accepted(ctx.messageId());
    }

    /** Codec-less convenience overload — payload bytes are empty. */
    public static DispatchResult<Void> acceptAndAppend(
            DomainEvent event, ExecutionContext ctx, OutboxStorage storage, Clock clock) {
        return acceptAndAppend(event, ctx, storage, clock, null);
    }
}
