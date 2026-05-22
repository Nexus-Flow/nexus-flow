package net.nexus_flow.core.ring.dispatch;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.command.CommandBus;
import net.nexus_flow.core.cqrs.command.CommandRegistrationSnapshot;
import net.nexus_flow.core.ring.registry.HandlerRole;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.ids.FastUuid;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowError;
import net.nexus_flow.core.types.TypeReference;
import org.jspecify.annotations.Nullable;

/**
 * Cross-pod fallback for the local {@link CommandBus}. When a caller dispatches a command
 * whose type has NO handler registered on the local bus, this adapter routes the command
 * across the ring via {@link RingDispatcher} to a peer that DOES advertise the handler.
 * The audit identified that {@code RingDispatcher} existed but was not wired into the
 * {@link CommandBus} dispatch path — cross-pod dispatch was a manual operation. The
 * fallback closes that gap as an opt-in adapter at the call site.
 *
 * <h2>Resolution order</h2>
 *
 * <ol>
 * <li>{@link CommandBus#registrationSnapshot()} is consulted for the command type.
 * <li>If the type is registered locally — delegate to
 * {@link CommandBus#dispatchAndReturnResult(Command, ExecutionContext, ErrorPolicy)}.
 * <li>If the type is NOT registered locally — route via
 * {@link RingDispatcher#dispatch} as a COMMAND envelope. The future is converted to
 * a {@link DispatchResult} matching the wire outcome.
 * </ol>
 *
 * <h2>Why opt-in, not always-on</h2>
 *
 * A globally-installed fallback would change the semantics of {@code dispatch} silently:
 * a missing handler used to fail immediately; with the fallback it would suddenly succeed
 * via a remote peer. Operators MUST choose this behaviour explicitly per command class —
 * either by wrapping selected dispatch sites in the fallback, or by composing a
 * {@code CommandBus} façade that uses the fallback for known cross-pod types.
 *
 * <h2>Payload encoding</h2>
 *
 * The command's payload record is encoded via the injected {@link CommandPayloadCodec} —
 * the same codec the framework uses for outbox events. The remote peer decodes via the
 * codec it has registered for the {@code codecId} discriminator and dispatches against
 * its own local CommandBus.
 *
 * <h2>Threading</h2>
 *
 * {@link #dispatchAndReturnResult} blocks the caller until either the local handler
 * returns OR the remote dispatch future completes within {@code timeout}. For
 * non-blocking semantics, use {@link #dispatchAsync} which returns the underlying
 * {@link CompletableFuture}.
 */
public final class RingCommandFallback {

    private final CommandBus          localBus;
    private final RingDispatcher      ringDispatcher;
    private final CommandPayloadCodec codec;
    private final String              codecId;
    private final ErrorPolicy         defaultErrorPolicy;

    private RingCommandFallback(
            CommandBus localBus,
            RingDispatcher ringDispatcher,
            CommandPayloadCodec codec,
            String codecId,
            ErrorPolicy defaultErrorPolicy) {
        this.localBus           = localBus;
        this.ringDispatcher     = ringDispatcher;
        this.codec              = codec;
        this.codecId            = codecId;
        this.defaultErrorPolicy = defaultErrorPolicy;
    }

    /**
     * Synchronous dispatch with cross-pod fallback. Returns a {@link DispatchResult}
     * carrying either the local handler's outcome or the cross-pod response.
     *
     * @param command the command to dispatch
     * @param ctx     execution context — propagates trace/correlation/causation across pods
     * @param timeout maximum wait for cross-pod responses; ignored when the command runs
     *                locally
     */
    public <T extends Record, R> DispatchResult<R> dispatchAndReturnResult(
            Command<T> command, ExecutionContext ctx, Duration timeout) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
        if (isLocallyRegistered(command)) {
            return localBus.dispatchAndReturnResult(command, ctx, defaultErrorPolicy);
        }
        try {
            DispatchResponseEnvelope env = dispatchAsync(command, ctx, timeout, null)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return mapToLocalResult(env);
        } catch (TimeoutException te) {
            return DispatchResult.failure(
                                          new FlowError.Technical("cross-pod dispatch timed out after " + timeout, te, ctx));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return DispatchResult.failure(
                                          new FlowError.Technical("cross-pod dispatch interrupted", ie, ctx));
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            return DispatchResult.failure(
                                          new FlowError.Technical("cross-pod dispatch failed: "
                                                  + cause.getMessage(), cause, ctx));
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
            return DispatchResult.failure(
                                          new FlowError.Technical("cross-pod dispatch failed: "
                                                  + cause.getMessage(), cause, ctx));
        }
    }

    /**
     * Asynchronous cross-pod dispatch. Returns the raw {@link CompletableFuture} of the
     * wire envelope — callers map to their own result shape. Use this when the command is
     * known to need cross-pod routing OR when integrating with a reactive caller.
     */
    public <T extends Record> CompletableFuture<DispatchResponseEnvelope> dispatchAsync(
            Command<T> command,
            ExecutionContext ctx,
            Duration timeout,
            @Nullable String routingKey) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(timeout, "timeout");
        byte[] body;
        try {
            body = codec.encode(command.getBody());
        } catch (RuntimeException encodeFail) {
            CompletableFuture<DispatchResponseEnvelope> failed = new CompletableFuture<>();
            failed.completeExceptionally(encodeFail);
            return failed;
        }
        UUID traceId       = ctx.traceId() == null ? FastUuid.v4() : ctx.traceId().value();
        UUID correlationId =
                ctx.correlationId() == null ? FastUuid.v4() : ctx.correlationId().value();
        UUID causationId   =
                ctx.causationId() == null ? FastUuid.v4() : ctx.causationId().value();
        return ringDispatcher.dispatch(
                                       HandlerRole.COMMAND,
                                       command.getBody().getClass().getName(),
                                       codecId,
                                       body,
                                       traceId,
                                       correlationId,
                                       causationId,
                                       null,
                                       timeout,
                                       routingKey);
    }

    /** @return {@code true} if the command's payload type has a local handler. */
    public <T extends Record> boolean isLocallyRegistered(Command<T> command) {
        CommandRegistrationSnapshot snapshot = localBus.registrationSnapshot();
        TypeReference<?>            ref      = new TypeReference<>(command.getBody().getClass());
        return snapshot.noReturnCommandTypes().contains(ref) || snapshot.returnCommandTypes().contains(ref);
    }

    @SuppressWarnings("unchecked")
    private static <R> DispatchResult<R> mapToLocalResult(DispatchResponseEnvelope envelope) {
        return switch (envelope.outcome()) {
            case SUCCESS, ACCEPTED                                -> {
                // The remote handler returned a result — we don't have the typed payload
                // (the wire form is bytes); callers needing R must decode via the same codec
                // the remote used. The fallback intentionally returns a typed Success with
                // null payload — callers that need the bytes use dispatchAsync directly.
                yield (DispatchResult<R>) DispatchResult.success(null);
            }
            case FAILURE, PARTIAL_FAILURE, FORBIDDEN, UNAVAILABLE -> DispatchResult.failure(
                                                                                            new FlowError.Technical(
                                                                                                    "cross-pod dispatch returned "
                                                                                                            + envelope.outcome()
                                                                                                            + " (" + envelope.errorCode()
                                                                                                            + ")"
                                                                                                            + (envelope.reason()
                                                                                                                    .isEmpty() ? "" : ": "
                                                                                                                            + envelope
                                                                                                                                    .reason()),
                                                                                                    null,
                                                                                                    ExecutionContext.root()));
            case NOT_FOUND                                        -> DispatchResult.failure(
                                                                                            new FlowError.Technical(
                                                                                                    "no peer advertises the handler: "
                                                                                                            + envelope.reason(),
                                                                                                    null,
                                                                                                    ExecutionContext.root()));
            case TIMEOUT                                          -> DispatchResult.failure(
                                                                                            new FlowError.Technical(
                                                                                                    "cross-pod handler timed out: "
                                                                                                            + envelope.reason(),
                                                                                                    null,
                                                                                                    ExecutionContext.root()));
        };
    }

    /** Fluent builder. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private CommandBus          localBus;
        private RingDispatcher      ringDispatcher;
        private CommandPayloadCodec codec;
        private String              codecId            = "java-v1";
        private ErrorPolicy         defaultErrorPolicy = ErrorPolicy.failFast();

        private Builder() {
        }

        public Builder localBus(CommandBus bus) {
            this.localBus = Objects.requireNonNull(bus, "bus");
            return this;
        }

        public Builder ringDispatcher(RingDispatcher dispatcher) {
            this.ringDispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
            return this;
        }

        /**
         * Set the codec used to encode command bodies for the wire. The codec's
         * {@link CommandPayloadCodec#codecId()} becomes the discriminator the remote peer
         * uses to look up its decoder; pass an explicit override via {@code codecId} when
         * the codec's default codecId is not stable across versions.
         */
        public Builder payloadCodec(CommandPayloadCodec codec) {
            this.codec   = Objects.requireNonNull(codec, "codec");
            this.codecId = codec.codecId();
            return this;
        }

        /** Override the codec id discriminator placed on the wire. */
        public Builder codecId(String codecId) {
            this.codecId = Objects.requireNonNull(codecId, "codecId");
            return this;
        }

        public Builder defaultErrorPolicy(ErrorPolicy policy) {
            this.defaultErrorPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public RingCommandFallback build() {
            Objects.requireNonNull(localBus, "localBus");
            Objects.requireNonNull(ringDispatcher, "ringDispatcher");
            Objects.requireNonNull(codec, "codec");
            return new RingCommandFallback(
                    localBus, ringDispatcher, codec, codecId, defaultErrorPolicy);
        }
    }
}
