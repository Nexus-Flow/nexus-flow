package net.nexus_flow.core.runtime.dispatch;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.jspecify.annotations.Nullable;

/**
 * Mutable per-dispatch context handed to every {@link DispatchInterceptor} in the chain.
 *
 * <p>An {@code InvocationContext} is materialized once per dispatch and never shared between
 * dispatches. Within a single dispatch it is shared by every link of the interceptor chain so that
 * observability metadata (timings, tracing flags, …) added by an outer interceptor remains visible
 * to its inner siblings <em>and</em> to the original caller after the chain unwinds.
 *
 * <p><strong>Fan-out invariant.</strong> A fan-out dispatch (one command → N events, one event → N
 * listeners) MUST create a distinct {@code InvocationContext} per sibling so that mutations
 * published by one sibling never leak to another. The runtime enforces this by never reusing an
 * {@code InvocationContext} across sibling forks.
 *
 * <p>Lifetime: identical to the dispatch it wraps. Thread-safety: the mutable {@link #attributes()}
 * bag is a {@link ConcurrentHashMap}, so concurrent interceptors (e.g. fan-out under {@code
 * CollectFailures}) can read/write without external synchronization. The other fields are immutable
 * {@code final}s.
 */
public final class InvocationContext {

    /**
     * Per-thread view of the {@link InvocationContext} flowing through the dispatcher onion.
     * {@code ThreadLocal} (not {@code ScopedValue}) — JMH validates {@code ThreadLocal.get()}
     * at ~0.7 ns per read vs {@code ScopedValue.where(...).call(...)} setup at ~13 ns per
     * dispatch. The dispatcher reads {@code current()} 0–2 times per dispatch, so the
     * per-dispatch setup cost of {@code ScopedValue} would dominate. {@code ScopedValue}
     * would win only if (a) we read many times per dispatch or (b) we relied on automatic
     * inheritance into {@link java.util.concurrent.StructuredTaskScope} child forks — neither
     * is the case here, so {@code ThreadLocal} is the technical pick.
     */
    private static final ThreadLocal<@Nullable InvocationContext> CURRENT = new ThreadLocal<>();

    public static java.util.Optional<InvocationContext> current() {
        return java.util.Optional.ofNullable(CURRENT.get());
    }

    /**
     * Package-private hook used exclusively by {@link SyncDispatcher} to bind / unbind the
     * thread-local current invocation.
     *
     * @param ctx the context to bind, or {@code null} to clear.
     */
    static void bindCurrent(@Nullable InvocationContext ctx) {
        if (ctx == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(ctx);
        }
    }

    private final InvocationKind                                                   kind;
    private final Object                                                           payload;
    private final ExecutionContext                                                 executionContext;
    private final ErrorPolicy                                                      errorPolicy;
    private final InvocationStage                                                  stage;
    /**
     * Lazily-allocated attribute bag. Most dispatches do not stamp attributes; that path
     * pays zero allocation here. The first read or write installs a fresh
     * {@link ConcurrentHashMap}; subsequent calls reuse it. Sibling fan-out clones an
     * InvocationContext via {@link #withStage} / {@link #withExecutionContext} that SHARE
     * this reference so attributes propagate through the chain.
     */
    private final java.util.concurrent.atomic.AtomicReference<Map<String, Object>> attributesRef;

    private InvocationContext(
            InvocationKind kind,
            Object payload,
            ExecutionContext executionContext,
            ErrorPolicy errorPolicy,
            InvocationStage stage,
            java.util.concurrent.atomic.AtomicReference<Map<String, Object>> attributesRef) {
        this.kind             = Objects.requireNonNull(kind, "kind");
        this.payload          = Objects.requireNonNull(payload, "payload");
        this.executionContext = Objects.requireNonNull(executionContext, "executionContext");
        this.errorPolicy      = Objects.requireNonNull(errorPolicy, "errorPolicy");
        this.stage            = Objects.requireNonNull(stage, "stage");
        this.attributesRef    = Objects.requireNonNull(attributesRef, "attributesRef");
    }

    /**
     * Create a fresh invocation context entering the chain at {@link InvocationStage#PRE} with an
     * empty mutable attribute bag.
     *
     * @param kind             classification of the dispatch (COMMAND / QUERY / EVENT); never {@code null}
     * @param payload          the message being dispatched; never {@code null}
     * @param executionContext the active {@link ExecutionContext} for this dispatch; never {@code
     *     null}
     * @param errorPolicy      the active {@link ErrorPolicy}; never {@code null}
     * @return a new {@code InvocationContext} at stage {@link InvocationStage#PRE} with an empty
     *         {@link java.util.concurrent.ConcurrentHashMap} attribute bag
     */
    public static InvocationContext of(
            InvocationKind kind,
            Object payload,
            ExecutionContext executionContext,
            ErrorPolicy errorPolicy) {
        return new InvocationContext(
                kind,
                payload,
                executionContext,
                errorPolicy,
                InvocationStage.PRE,
                new java.util.concurrent.atomic.AtomicReference<>());
    }

    /**
     * Classification of the dispatch (COMMAND / QUERY / EVENT).
     *
     * @return the {@link InvocationKind} set at construction; never {@code null}
     */
    public InvocationKind kind() {
        return kind;
    }

    /**
     * The message payload being dispatched.
     *
     * @return the raw payload object passed to the bus; never {@code null}
     */
    public Object payload() {
        return payload;
    }

    /**
     * The {@link ExecutionContext} active for this dispatch, carrying trace/correlation/causation
     * ids, optional deadline, and the cooperative cancellation token.
     *
     * @return the execution context; never {@code null}
     */
    public ExecutionContext executionContext() {
        return executionContext;
    }

    /**
     * The error policy governing this dispatch.
     *
     * @return the active {@link ErrorPolicy}; never {@code null}
     */
    public ErrorPolicy errorPolicy() {
        return errorPolicy;
    }

    /**
     * The lifecycle stage at which this context snapshot was taken ({@link InvocationStage#PRE},
     * {@link InvocationStage#INVOKE}, or {@link InvocationStage#POST}).
     *
     * @return the stage; never {@code null}
     */
    public InvocationStage stage() {
        return stage;
    }

    /**
     * Live, thread-safe attribute bag shared by every link of the interceptor chain for this
     * dispatch. Interceptors publish ephemeral observability data here (e.g. {@code
     * dispatch.durationMs} from the canonical {@code TimingDispatchInterceptor}).
     *
     * <p>Not propagated to sibling fan-out invocations; see class Javadoc.
     */
    public Map<String, Object> attributes() {
        Map<String, Object> a = attributesRef.get();
        if (a != null) {
            return a;
        }
        Map<String, Object> fresh = new ConcurrentHashMap<>();
        if (attributesRef.compareAndSet(null, fresh)) {
            return fresh;
        }
        return attributesRef.get();
    }

    /**
     * Return a view of this context stamped with a different {@link InvocationStage}. The mutable
     * attribute bag is shared with the source so writes are still observable to the caller.
     *
     * @param newStage the stage to stamp; must not be {@code null}
     * @return {@code this} when {@code newStage == this.stage()}, otherwise a new {@code
     *     InvocationContext} sharing all fields except the stage
     */
    public InvocationContext withStage(InvocationStage newStage) {
        if (newStage == this.stage) {
            return this;
        }
        return new InvocationContext(
                kind, payload, executionContext, errorPolicy, newStage, attributesRef);
    }

    /**
     * Return a view of this context with a replaced {@link ExecutionContext} (e.g. an interceptor
     * that adds a baggage attribute via {@link ExecutionContext#withAttribute(String, Object)}). The
     * mutable invocation attribute bag is shared with the source.
     *
     * @param newExecutionContext the replacement execution context; must not be {@code null}
     * @return {@code this} when {@code newExecutionContext == this.executionContext()}, otherwise a
     *         new {@code InvocationContext} with the supplied execution context and all other fields
     *         unchanged
     * @throws NullPointerException if {@code newExecutionContext} is {@code null}
     */
    public InvocationContext withExecutionContext(ExecutionContext newExecutionContext) {
        Objects.requireNonNull(newExecutionContext, "newExecutionContext");
        if (newExecutionContext == this.executionContext) {
            return this;
        }
        return new InvocationContext(
                kind, payload, newExecutionContext, errorPolicy, stage, attributesRef);
    }
}
