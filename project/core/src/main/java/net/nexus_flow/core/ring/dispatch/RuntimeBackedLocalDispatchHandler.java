package net.nexus_flow.core.ring.dispatch;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.command.CommandBus;
import net.nexus_flow.core.cqrs.command.CommandRegistrationSnapshot;
import net.nexus_flow.core.cqrs.query.Query;
import net.nexus_flow.core.cqrs.query.QueryBus;
import net.nexus_flow.core.ring.wire.ProtocolErrorCode;
import net.nexus_flow.core.runtime.CancellationToken;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.FlowScope;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TenantId;
import net.nexus_flow.core.runtime.ids.TraceId;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.types.TypeReference;

/**
 * {@link LocalDispatchHandler} that bridges every inbound cross-pod COMMAND_REQ / QUERY_REQ to
 * the bound {@link FlowRuntime}'s {@link CommandBus} / {@link QueryBus}. Closes the gap
 * where the default in-tree handler rejected every inbound dispatch with {@link
 * ProtocolErrorCode#NOT_FOUND} — a single-pod ring was the only useful configuration.
 *
 * <h2>End-to-end flow</h2>
 *
 * <ol>
 * <li>{@link RingFrameRouter} authorises the request via {@link DispatchAuthorizer} (already
 * applied before this handler is called); deadline-expired requests short-circuit at the
 * router before invoking the handler.
 * <li>The handler resolves {@link DispatchRequestEnvelope#payloadType()} to a {@link Record}
 * class through the configured {@link ClassResolver}. Non-record classes are rejected as
 * {@link ProtocolErrorCode#INVALID_REQUEST}.
 * <li>The configured {@link CommandPayloadCodec} decodes the payload bytes to a {@code
 *       Record} instance.
 * <li>A child {@link ExecutionContext} is constructed from the wire envelope's trace /
 * correlation / causation ids, the receiver's local-clock deadline (already computed by
 * the router), and the request's tenant.
 * <li>The dispatch goes through {@link CommandBus#dispatchAndReturnResult} (for COMMAND) or
 * {@link QueryBus#ask} (for QUERY) under {@link FlowScope#runWithContext} so any
 * downstream code reading {@link FlowScope#current()} observes the wire context.
 * <li>The {@link DispatchResult} or query value is mapped to a {@link
 * DispatchResponseEnvelope} with a sanitised reason string — raw exception messages
 * never cross back to the sender.
 * </ol>
 *
 * <h2>Response body</h2>
 *
 * The wire response carries an outcome code and a sanitised reason but does NOT carry the
 * typed result payload. The sender's {@link RingCommandFallback} surfaces {@link
 * DispatchResult.Success} with a {@code null} value and asks callers that need the typed
 * result to model it as a separate domain event flowing through the outbox / event bus. This
 * matches the existing cross-pod fallback contract.
 *
 * <h2>Security: payload class resolution</h2>
 *
 * The handler uses Java reflection to load the payload class identified by name on the wire.
 * Under mTLS the sender is authenticated, but a compromised peer could still nominate an
 * arbitrary class name. The {@link DispatchAuthorizer} is the primary gate (it filters by
 * principal + role + tenant + payloadType BEFORE this handler runs), and the {@link
 * ClassResolver} adds defence in depth — operators install an allowlist via {@link
 * Builder#classResolver(ClassResolver)} when the default {@code Class.forName} reflection is
 * too permissive for their threat model.
 *
 * <h2>Thread-safety</h2>
 *
 * Stateless. Concurrent inbound frames each carry their own {@link LocalDispatchContext}; the
 * handler routes them in parallel through the bound buses.
 */
public final class RuntimeBackedLocalDispatchHandler implements LocalDispatchHandler {

    private static final System.Logger LOG =
            System.getLogger(RuntimeBackedLocalDispatchHandler.class.getName());

    /**
     * Pluggable strategy that resolves a wire payload type name to a JVM {@link Class}. The
     * default resolution uses {@link Class#forName(String, boolean, ClassLoader)} against the
     * thread's context class loader. Operators install a tighter resolver (allowlist, package
     * prefix filter) via {@link Builder#classResolver(ClassResolver)} when reflection on
     * arbitrary names is unacceptable for their threat model.
     */
    @FunctionalInterface
    public interface ClassResolver {
        /**
         * @throws ClassNotFoundException when the name is unknown OR refused by an allowlist
         */
        Class<?> resolve(String fullyQualifiedName) throws ClassNotFoundException;

        /** Default resolver — {@code Class.forName} against the context class loader. */
        ClassResolver CONTEXT_CLASSLOADER = RuntimeBackedLocalDispatchHandler::resolveWithContextClassLoader;

        /** Allowlist factory — only resolves names in the supplied set. */
        static ClassResolver allowlist(Set<String> allowedFqns) {
            Objects.requireNonNull(allowedFqns, "allowedFqns");
            Set<String> snapshot = Set.copyOf(allowedFqns);
            return name -> {
                if (!snapshot.contains(name)) {
                    throw new ClassNotFoundException(
                            "type not in allowlist: " + name);
                }
                return CONTEXT_CLASSLOADER.resolve(name);
            };
        }
    }

    private final FlowRuntime         runtime;
    private final CommandPayloadCodec codec;
    private final ClassResolver       classResolver;

    private RuntimeBackedLocalDispatchHandler(Builder b) {
        this.runtime       = b.runtime;
        this.codec         = b.codec;
        this.classResolver = b.classResolver;
    }

    @Override
    public DispatchResponseEnvelope dispatch(LocalDispatchContext ctx) {
        DispatchRequestEnvelope request = ctx.request();
        if (ctx.isDeadlineExpired()) {
            return DispatchResponseEnvelope.timeout(
                                                    request.correlationId(), "deadline expired before local dispatch");
        }
        Class<?> payloadClass;
        try {
            payloadClass = classResolver.resolve(request.payloadType());
        } catch (ClassNotFoundException cnf) {
            return DispatchResponseEnvelope.notFound(
                                                     request.correlationId(),
                                                     "payload type not resolvable on this peer");
        }
        if (!payloadClass.isRecord()) {
            return DispatchResponseEnvelope.failure(
                                                    request.correlationId(),
                                                    ProtocolErrorCode.INVALID_REQUEST,
                                                    "payload type is not a record");
        }
        Record body;
        try {
            @SuppressWarnings("unchecked") Class<? extends Record> recordType = (Class<? extends Record>) payloadClass;
            body = codec.decode(request.payloadBytes(), recordType);
        } catch (RuntimeException decodeFail) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "runtime-backed dispatch: codec decode failed for "
                            + request.payloadType() + ": " + decodeFail.getMessage());
            return DispatchResponseEnvelope.failure(
                                                    request.correlationId(),
                                                    ProtocolErrorCode.INVALID_REQUEST,
                                                    "payload bytes do not decode under codec "
                                                            + request.codecId());
        }
        ExecutionContext childCtx = buildChildContext(request, ctx);
        return switch (ctx.role()) {
            case COMMAND -> dispatchCommand(request, body, childCtx);
            case QUERY   -> dispatchQuery(request, body, childCtx);
        };
    }

    private DispatchResponseEnvelope dispatchCommand(
            DispatchRequestEnvelope request, Record body, ExecutionContext childCtx) {
        // The CommandBus splits its registry between fire-and-forget NoReturnCommandHandler
        // (only reachable via dispatch(Command)) and value-producing ReturnCommandHandler
        // (only reachable via dispatchAndReturnResult). The right routing is determined by
        // which set the payload type lives in — both never overlap.
        CommandRegistrationSnapshot snapshot   = runtime.commands().registrationSnapshot();
        TypeReference<?>            payloadRef = new TypeReference<>(body.getClass());
        if (snapshot.returnCommandTypes().contains(payloadRef)) {
            return dispatchReturnCommand(request, body, childCtx);
        }
        if (snapshot.noReturnCommandTypes().contains(payloadRef)) {
            return dispatchNoReturnCommand(request, body, childCtx);
        }
        return DispatchResponseEnvelope.notFound(
                                                 request.correlationId(),
                                                 "no command handler registered for " + request.payloadType());
    }

    private DispatchResponseEnvelope dispatchReturnCommand(
            DispatchRequestEnvelope request, Record body, ExecutionContext childCtx) {
        try {
            DispatchResult<?> result = invokeReturnCommandTyped(body, childCtx);
            return mapResultToWire(request, result);
        } catch (RuntimeException dispatchFail) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "runtime-backed dispatch: COMMAND " + request.payloadType()
                            + " threw outside the result envelope: " + dispatchFail.getMessage(),
                    dispatchFail);
            return DispatchResponseEnvelope.failure(
                                                    request.correlationId(),
                                                    ProtocolErrorCode.INTERNAL,
                                                    "local handler internal error");
        }
    }

    private DispatchResponseEnvelope dispatchNoReturnCommand(
            DispatchRequestEnvelope request, Record body, ExecutionContext childCtx) {
        try {
            invokeNoReturnCommandTyped(body, childCtx);
            // Fire-and-forget — the wire signal is ACCEPTED (the command was enqueued; the
            // handler's eventual outcome is not reported through this response). Callers that
            // need typed success / failure MUST use a ReturnCommandHandler and the return
            // path.
            return DispatchResponseEnvelope.accepted(
                                                     request.correlationId(), "", "", new byte[0]);
        } catch (RuntimeException dispatchFail) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "runtime-backed dispatch: COMMAND " + request.payloadType()
                            + " (no-return) threw at enqueue: " + dispatchFail.getMessage(),
                    dispatchFail);
            return DispatchResponseEnvelope.failure(
                                                    request.correlationId(),
                                                    ProtocolErrorCode.INTERNAL,
                                                    "local handler internal error");
        }
    }

    private DispatchResponseEnvelope dispatchQuery(
            DispatchRequestEnvelope request, Record body, ExecutionContext childCtx) {
        try {
            invokeQueryTyped(body, childCtx);
            return DispatchResponseEnvelope.success(
                                                    request.correlationId(), "", "", new byte[0]);
        } catch (RuntimeException askFail) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "runtime-backed dispatch: QUERY " + request.payloadType()
                            + " threw: " + askFail.getClass().getSimpleName(), askFail);
            return DispatchResponseEnvelope.failure(
                                                    request.correlationId(),
                                                    ProtocolErrorCode.INTERNAL,
                                                    "query handler internal error");
        }
    }

    /**
     * Typed helper for the value-returning COMMAND path — the type parameter {@code T} lets us
     * call the typed {@link CommandBus#dispatchAndReturnResult} without raw types, even though
     * the runtime payload type is only known through reflection. {@code T} is inferred as
     * {@code Record} (the bound) at the call site.
     */
    private <T extends Record> DispatchResult<?> invokeReturnCommandTyped(T body, ExecutionContext ctx) {
        Command<T> command = Command.<T>builder().body(body).build();
        return FlowScope.getWithContext(
                                        ctx,
                                        () -> runtime.commands().dispatchAndReturnResult(
                                                                                         command, ctx, ErrorPolicy.failFast()));
    }

    /**
     * Typed helper for the fire-and-forget COMMAND path. The bus's {@code dispatch(Command,
     * ExecutionContext)} overload binds {@link FlowScope#runWithContext} internally so the
     * handler sees the wire-derived context.
     */
    private <T extends Record> void invokeNoReturnCommandTyped(T body, ExecutionContext ctx) {
        Command<T> command = Command.<T>builder().body(body).build();
        runtime.commands().dispatch(command, ctx);
    }

    /** Typed helper for the QUERY path — mirrors {@link #invokeReturnCommandTyped}. */
    private <T extends Record> Object invokeQueryTyped(T body, ExecutionContext ctx) {
        Query<T> query = Query.<T>builder().body(body).build();
        return FlowScope.getWithContext(
                                        ctx,
                                        () -> runtime.queries().ask(query));
    }

    private static DispatchResponseEnvelope mapResultToWire(
            DispatchRequestEnvelope request, DispatchResult<?> result) {
        return switch (result) {
            case DispatchResult.Success<?> _        -> DispatchResponseEnvelope.success(
                                                                                        request.correlationId(), "", "", new byte[0]);
            case DispatchResult.Accepted<?> _       -> DispatchResponseEnvelope.accepted(
                                                                                         request.correlationId(), "", "", new byte[0]);
            case DispatchResult.Failure<?> _        -> DispatchResponseEnvelope.failure(
                                                                                        request.correlationId(),
                                                                                        ProtocolErrorCode.INTERNAL,
                                                                                        "command handler failed");
            case DispatchResult.PartialFailure<?> _ -> DispatchResponseEnvelope.partialFailure(
                                                                                               request.correlationId(), "", "", new byte[0],
                                                                                               "partial failure during fan-out");
        };
    }

    private static ExecutionContext buildChildContext(
            DispatchRequestEnvelope request, LocalDispatchContext ctx) {
        Instant  deadline = ctx.localDeadlineNanos().isPresent() ? Instant.ofEpochMilli(
                                                                                        System.currentTimeMillis() + Math.max(0L, (ctx
                                                                                                .localDeadlineNanos().getAsLong() - System
                                                                                                        .nanoTime()) / 1_000_000L)) : null;
        TenantId tenant   = request.tenantId() == null ? null : new TenantId(request.tenantId());
        return new ExecutionContext(
                MessageId.random(),
                new TraceId(request.traceId()),
                new CorrelationId(request.contextCorrelationId()),
                new CausationId(request.causationId()),
                tenant,
                /* principal */ null,
                deadline,
                CancellationToken.create(),
                Map.of());
    }

    /**
     * Default class resolution: try the calling thread's context class loader first, then fall
     * back to this class's own loader (some hosts — JEE app servers, OSGi — leave the context
     * loader unset on framework threads). The fallback is a known-safe library pattern; the
     * PMD {@code UseProperClassLoader} rule is silenced because the context loader IS already
     * the primary path here.
     */
    @SuppressWarnings("PMD.UseProperClassLoader")
    static Class<?> resolveWithContextClassLoader(String name) throws ClassNotFoundException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = RuntimeBackedLocalDispatchHandler.class.getClassLoader();
        }
        return Class.forName(name, false, loader);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder. {@code runtime} and {@code codec} are mandatory; the rest has defaults. */
    public static final class Builder {

        private FlowRuntime         runtime;
        private CommandPayloadCodec codec;
        private ClassResolver       classResolver = ClassResolver.CONTEXT_CLASSLOADER;

        private Builder() {
        }

        public Builder runtime(FlowRuntime runtime) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            return this;
        }

        public Builder codec(CommandPayloadCodec codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        public Builder classResolver(ClassResolver resolver) {
            this.classResolver = Objects.requireNonNull(resolver, "classResolver");
            return this;
        }

        public RuntimeBackedLocalDispatchHandler build() {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(codec, "codec");
            return new RuntimeBackedLocalDispatchHandler(this);
        }
    }
}
