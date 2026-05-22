package net.nexus_flow.core.runtime.dispatch;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.StructuredTaskScope.Subtask;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowScope;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import net.nexus_flow.core.runtime.result.FlowDeadlineExceededException;
import net.nexus_flow.core.runtime.result.FlowError;
import org.jspecify.annotations.Nullable;

/**
 * Synchronous structured dispatcher.
 *
 * <p>Wraps every handler invocation in {@link FlowScope#getWithContext(ExecutionContext,
 * java.util.function.Supplier)} so the active {@link ExecutionContext} is implicitly available
 * throughout the dispatch, classifies any {@link Throwable} into the {@link FlowError} taxonomy,
 * and uses {@link StructuredTaskScope} for fan-out cases (one command spawning N events, or one
 * event with N listeners) with a {@link Joiner} chosen from the active {@link ErrorPolicy}.
 *
 * <p>Mapping of {@link ErrorPolicy} → {@link Joiner}:
 *
 * <ul>
 * <li>{@link ErrorPolicy.FailFast} → {@link Joiner#awaitAllSuccessfulOrThrow()}. The first
 * failure cancels every sibling fork; remaining concurrent failures arrive as suppressed
 * exceptions on the winning cause via {@link FlowError.Aggregated}.
 * <li>{@link ErrorPolicy.CollectFailures} → {@link Joiner#awaitAll()} + manual aggregation into
 * {@link DispatchResult.PartialFailure} or {@link FlowError.Aggregated}.
 * <li>{@link ErrorPolicy.IsolatePerBoundary} → a child scope that runs {@code inner} but never
 * propagates failures to its parent. Boundaries do not nest.
 * <li>{@link ErrorPolicy.IgnoreFailures} → {@link Joiner#awaitAllSuccessfulOrThrow()} but
 * failures matching the predicate are silently dropped before being surfaced.
 * </ul>
 *
 * <p>Honours {@link ExecutionContext#throwIfCancelledOrExpired()} before each fork.
 */
public final class SyncDispatcher {

    private SyncDispatcher() {
    }

    // ------------------------------------------------------------------
    // interceptor onion.
    //
    // The interceptor framework is a *layer on top* of invoke/fanOut: it
    // never touches the FailFast / CollectFailures / IsolatePerBoundary
    // bodies below, so an empty interceptor list yields a result that is
    // byte-identical to the pre-2.4 dispatcher (regression tests verify this).
    //
    // Lifetime: the chain is materialized once per call to
    // dispatchThrough(...) and never reused across threads. The
    // InvocationContext attribute bag is shared by every link of *this*
    // chain only — fan-out siblings get their own bag.
    // ------------------------------------------------------------------

    /**
     * Run {@code terminal} (a function that performs the underlying dispatch and returns a {@link
     * DispatchResult}) through the registration-ordered onion of {@code interceptors}.
     *
     * <p>With zero interceptors the chain degenerates to a direct call to {@code terminal.call()} —
     * no allocation, no wrapping, no behavior change.
     *
     * <p>Error wrapping:
     *
     * <ul>
     * <li>{@link FlowError.Domain} thrown by an interceptor → returned verbatim as {@link
     * DispatchResult.Failure}.
     * <li>Anything else thrown by an interceptor → wrapped in a {@link FlowError.Technical}
     * carrying the failing {@link InvocationContext} (stage = PRE / INVOKE / POST).
     * <li>An interceptor that calls {@code chain.proceed()} and receives a {@link
     * DispatchResult.Failure} / {@link DispatchResult.PartialFailure} MUST NOT return a {@link
     * DispatchResult.Success}. Violations are detected here and surfaced as a {@code Technical}
     * with an {@link IllegalStateException} cause.
     * </ul>
     */
    public static <R> DispatchResult<R> dispatchThrough(
            InvocationContext invCtx,
            List<? extends DispatchInterceptor> interceptors,
            Callable<DispatchResult<R>> terminal) {
        Objects.requireNonNull(invCtx, "invCtx");
        Objects.requireNonNull(interceptors, "interceptors");
        Objects.requireNonNull(terminal, "terminal");

        // Fast path: zero interceptors == pre-2.4 behavior, byte-identical.
        if (interceptors.isEmpty()) {
            InvocationContext previous = InvocationContext.current().orElse(null);
            InvocationContext.bindCurrent(invCtx);
            try {
                DispatchResult<R> r = terminal.call();
                if (r == null) {
                    throw new IllegalStateException("terminal returned null DispatchResult");
                }
                return r;
            } catch (Exception ex) {
                return SyncDispatcher.classify(ex, invCtx.executionContext(), invCtx.errorPolicy());
            } finally {
                InvocationContext.bindCurrent(previous);
            }
        }

        // Build the chain from the tail back to the head. The terminal
        // link wraps the user-supplied Callable; each interceptor link
        // wraps the previous one (so the *first* interceptor registered
        // ends up the outermost shell — onion semantics, see
        // {@link DispatchInterceptor} Javadoc).
        DispatchChain<R> chain = new TerminalChain<>(terminal, invCtx);
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            chain = new InterceptorChain<>(interceptors.get(i), invCtx, chain);
        }
        InvocationContext previous = InvocationContext.current().orElse(null);
        InvocationContext.bindCurrent(invCtx);
        try {
            return chain.proceed();
        } finally {
            InvocationContext.bindCurrent(previous);
        }
    }

    /**
     * Bottom of the onion: invokes the user-supplied {@link Callable} and classifies any throwable
     * that escapes it. The terminal chain is stamped {@link InvocationStage#INVOKE} for failure
     * attribution.
     */
    private record TerminalChain<R>(Callable<DispatchResult<R>> terminal, InvocationContext invCtx)
            implements DispatchChain<R> {

        @Override
        public DispatchResult<R> proceed() {
            try {
                DispatchResult<R> r = terminal.call();
                if (r == null) {
                    throw new IllegalStateException("terminal returned null DispatchResult");
                }
                return r;
            } catch (Exception ex) {
                return SyncDispatcher.classify(ex, invCtx.executionContext(), invCtx.errorPolicy());
            }
        }
    }

    /**
     * One link of the onion. Tracks whether {@code chain.proceed()} was called by the interceptor so
     * a throw can be attributed to PRE (not yet proceeded) or POST (proceeded and returned).
     */
    private record InterceptorChain<R>(
                                       DispatchInterceptor interceptor, InvocationContext invCtx, DispatchChain<R> next)
            implements DispatchChain<R> {

        @Override
        public DispatchResult<R> proceed() {
            // Track the inner proceeds outcome so we can enforce the
            // "no silent Failure → Success upgrade" invariant.
            ProceedTracker<R> tracker = new ProceedTracker<>(next);
            InvocationContext preCtx  = invCtx.withStage(InvocationStage.PRE);
            DispatchResult<R> outerResult;
            try {
                outerResult = interceptor.intercept(preCtx, tracker);
            } catch (Throwable t) {
                // domain errors propagate verbatim (Domain
                // is a marker interface, but its implementors must extend
                // Throwable per the FlowError.Domain contract).
                if (t instanceof FlowError.Domain) {
                    return DispatchResult.failure(t);
                }
                InvocationStage failingStage =
                        tracker.proceedReturned ? InvocationStage.POST : InvocationStage.PRE;
                return wrapInterceptorFailure(t, interceptor, invCtx.withStage(failingStage));
            }
            if (outerResult == null) {
                return wrapInterceptorFailure(
                                              new IllegalStateException(
                                                      "DispatchInterceptor returned null: " + interceptor.getClass().getName()),
                                              interceptor,
                                              invCtx.withStage(InvocationStage.POST));
            }
            // Enforce: a Failure/PartialFailure observed inside proceed()
            // cannot be silently upgraded to Success by the surrounding
            // interceptor. Transforming it (e.g. enriching the cause)
            // remains allowed.
            if (tracker.proceedReturned && !(tracker.innerResult instanceof DispatchResult.Success<?>) && outerResult instanceof DispatchResult.Success<R>) {
                return wrapInterceptorFailure(
                                              new IllegalStateException(
                                                      "DispatchInterceptor silently converted a non-Success "
                                                              + "inner result into Success: "
                                                              + interceptor.getClass().getName()),
                                              interceptor,
                                              invCtx.withStage(InvocationStage.POST));
            }
            return outerResult;
        }

        private static <R> DispatchResult<R> wrapInterceptorFailure(
                Throwable t, DispatchInterceptor interceptor, InvocationContext failingCtx) {
            // FlowError variants stay verbatim (Domain handled above; Technical/
            // Aggregated already carry their own metadata).
            if (t instanceof FlowError) {
                return DispatchResult.failure(t);
            }
            if (t instanceof FlowCancellationException || t instanceof FlowDeadlineExceededException) {
                return DispatchResult.failure(t);
            }
            String message =
                    "Interceptor "
                            + interceptor.getClass().getName()
                            + " failed at stage="
                            + failingCtx.stage()
                            + " kind="
                            + failingCtx.kind();
            return DispatchResult.failure(
                                          new FlowError.Technical(message, t, failingCtx.executionContext()));
        }
    }

    /**
     * Wrapper around the inner {@link DispatchChain} that records whether {@code proceed()} was
     * called and what it returned. The recorded state is consulted by {@link
     * InterceptorChain#proceed()} to (a) attribute interceptor failures to PRE vs. POST and (b)
     * enforce the no-silent-upgrade invariant.
     */
    private static final class ProceedTracker<R> implements DispatchChain<R> {
        private final DispatchChain<R> inner;
        boolean                        proceedReturned;
        // Populated only after proceed() returns; @Nullable because it is set lazily.
        @Nullable DispatchResult<R> innerResult;

        ProceedTracker(DispatchChain<R> inner) {
            this.inner = inner;
        }

        @Override
        public DispatchResult<R> proceed() {
            // The inner link runs at stage=INVOKE from the outer
            // interceptor's perspective; stage is consulted on failure
            // by InterceptorChain (using invCtx for the outer's PRE/POST).
            // We do not flip 'proceedReturned' until the inner returns so
            // that a throw *inside* proceed() (i.e. raised by the inner
            // chain and not caught by the outer interceptor) is attributed
            // to the inner link, not the outer.
            DispatchResult<R> r = inner.proceed();
            this.innerResult     = r;
            this.proceedReturned = true;
            return r;
        }
    }

    /**
     * Invoke a single handler synchronously under {@code ctx}. The handler's {@link Throwable} is
     * classified and wrapped appropriately.
     *
     * @param handler callable producing the success value
     * @param ctx     active execution context — bound through {@link FlowScope} for the duration of the
     *                call
     * @param policy  error policy used for {@link ErrorPolicy.IgnoreFailures} filtering (otherwise
     *                inert for a single-handler dispatch)
     */
    public static <R> DispatchResult<R> invoke(
            Callable<R> handler, ExecutionContext ctx, ErrorPolicy policy) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(policy, "policy");
        try {
            ctx.throwIfCancelledOrExpired();
            R value =
                    FlowScope.getWithContext(
                                             ctx,
                                             () -> {
                                                 try {
                                                     return handler.call();
                                                 } catch (RuntimeException re) {
                                                     throw re;
                                                 } catch (Exception ex) {
                                                     throw new HandlerCheckedException(ex);
                                                 }
                                             });
            return DispatchResult.success(value);
        } catch (HandlerCheckedException wrapped) {
            return classify(wrapped.getCause(), ctx, policy);
        } catch (Throwable t) {
            return classify(t, ctx, policy);
        }
    }

    /**
     * Drain the events recorded by a handler and publish them through {@code eventBus} using the
     * structured fan-out strategy chosen by {@code policy}.
     *
     * <p>Used by the new {@code CommandBus#dispatchAndReturnResult(...)} path after the handler
     * returns successfully.
     *
     * <p>Events are published <strong>sequentially</strong> in the order they were recorded on the
     * aggregate. Event&nbsp;N+1 starts only after every listener of event&nbsp;N has returned
     * (success or failure). The error policy still governs how listener failures are folded (within a
     * single event) and how event failures aggregate across the sequence (see the policy semantics
     * below). Sequentialization is the precondition for the durable outbox: a persisting listener
     * registered last sees the events in the exact order the aggregate emitted them.
     *
     * @param events   ordered list of recorded events; empty list ⇒ success
     * @param ctx      parent execution context — children derive from it
     * @param policy   fan-out error policy
     * @param eventBus event bus to delegate per-event dispatch to
     */
    public static DispatchResult<Void> publishEvents(
            List<DomainEvent> events, ExecutionContext ctx, ErrorPolicy policy, EventBus eventBus) {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(eventBus, "eventBus");
        if (events.isEmpty()) {
            return DispatchResult.success(null);
        }
        try {
            ctx.throwIfCancelledOrExpired();
        } catch (Throwable t) {
            return classify(t, ctx, policy);
        }

        // strict sequential iteration. Each event is
        // dispatched through the typed dispatchResult so its listeners
        // are themselves serialized (see DefaultEventBus.dispatchResult).
        // The accumulator stays at 4-slot initial capacity since multi-event partial-failure
        // batches are rare; growth from 4 is still O(1) amortised but matches the common case.
        List<Throwable> accumulated = new ArrayList<>(4);
        for (DomainEvent event : events) {
            try {
                ctx.throwIfCancelledOrExpired();
            } catch (Throwable t) {
                // Cancellation/expiration must short-circuit regardless of policy.
                return classify(t, ctx, policy);
            }
            ExecutionContext     childCtx    = ctx.childContextFor(MessageId.random());
            DispatchResult<Void> eventResult =
                    FlowScope.getWithContext(
                                             childCtx, () -> eventBus.dispatchResult(event, childCtx, policy));
            // Fold this event's result into the accumulated outcome.
            DispatchResult<Void> folded = foldOnDispatchResult(eventResult, accumulated, policy);
            if (folded != null) {
                // Short-circuit (e.g. FailFast hit a Failure).
                return folded;
            }
        }
        if (accumulated.isEmpty()) {
            return DispatchResult.success(null);
        }
        // Across-event aggregation: any accumulated failure becomes a
        // PartialFailure regardless of policy (CollectFailures /
        // IsolatePerBoundary already produced this shape per event;
        // IgnoreFailures only accumulates non-ignored causes).
        return DispatchResult.partial(null, accumulated);
    }

    /**
     * Fold one event's {@link DispatchResult} into the running accumulator following the cross-event
     * semantics of {@code policy}:
     *
     * <ul>
     * <li>{@link ErrorPolicy.FailFast} — first {@code Failure} short-circuits the loop; partials
     * accumulate.
     * <li>{@link ErrorPolicy.CollectFailures} — every failure cause accumulates; never
     * short-circuit.
     * <li>{@link ErrorPolicy.IgnoreFailures} — failures matching the predicate are dropped;
     * otherwise treat like FailFast.
     * <li>{@link ErrorPolicy.IsolatePerBoundary} — each event is already isolated; aggregate
     * without short-circuiting.
     * </ul>
     *
     * @return {@code null} to keep iterating; otherwise the terminal dispatch result to return
     *         immediately.
     */
    private static @Nullable DispatchResult<Void> foldOnDispatchResult(
            DispatchResult<Void> eventResult, List<Throwable> accumulated, ErrorPolicy policy) {
        return switch (eventResult) {
            case DispatchResult.Success<Void> _        -> null;
            case DispatchResult.Failure<Void> f        ->
                 switch (policy) {
                                                            case ErrorPolicy.FailFast _          -> DispatchResult.failure(f.cause());
                                                            case ErrorPolicy.CollectFailures _   -> {
                                                                accumulated.add(f.cause());
                                                                yield null;
                                                            }
                                                            case ErrorPolicy.IgnoreFailures ig   -> {
                                                                if (ig.predicate().test(f.cause())) {
                                                                    yield null;
                                                                }
                                                                yield DispatchResult.failure(f.cause());
                                                            }
                                                            case ErrorPolicy.IsolatePerBoundary _ -> {
                                                                accumulated.add(f.cause());
                                                                yield null;
                                                            }
                                                        };
            case DispatchResult.PartialFailure<Void> p -> {
                accumulated.addAll(p.failures());
                yield null;
            }
            // Accepted means the event was durably queued;
            // no synchronous failure to fold.
            case DispatchResult.Accepted<Void> _ -> null;
        };
    }

    /**
     * Generic fan-out under {@code policy}. Returns a unit {@link DispatchResult} (the value carried
     * on success is {@code null}; use the typed overload when the forks produce a meaningful result).
     */
    public static DispatchResult<Void> fanOut(
            List<? extends Callable<Void>> tasks, ExecutionContext ctx, ErrorPolicy policy) {
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(policy, "policy");
        if (tasks.isEmpty()) {
            return DispatchResult.success(null);
        }
        try {
            ctx.throwIfCancelledOrExpired();
        } catch (Throwable t) {
            return classify(t, ctx, policy);
        }
        return switch (policy) {
            case ErrorPolicy.FailFast _             -> failFast(tasks, ctx, ErrorPolicy.failFast());
            case ErrorPolicy.CollectFailures _      -> collectFailures(tasks, ctx);
            case ErrorPolicy.IgnoreFailures ig      -> ignoreFailures(tasks, ctx, ig);
            case ErrorPolicy.IsolatePerBoundary iso -> isolate(tasks, ctx, iso);
        };
    }

    // ------------------------------------------------------------------
    // Per-policy implementations.
    // ------------------------------------------------------------------

    private static DispatchResult<Void> failFast(
            List<? extends Callable<Void>> tasks, ExecutionContext ctx, ErrorPolicy policy) {
        try (var scope = StructuredTaskScope.open(Joiner.awaitAllSuccessfulOrThrow())) {
            for (Callable<Void> task : tasks) {
                ctx.throwIfCancelledOrExpired();
                scope.fork(task);
            }
            scope.join();
            return DispatchResult.success(null);
        } catch (StructuredTaskScope.FailedException fe) {
            // Aggregate concurrent losers as suppressed.
            Throwable cause = fe.getCause() != null ? fe.getCause() : fe;
            return classify(cause, ctx, policy);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return DispatchResult.failure(FlowCancellationException.CANCELLED);
        } catch (Throwable t) {
            return classify(t, ctx, policy);
        }
    }

    private static DispatchResult<Void> collectFailures(
            List<? extends Callable<Void>> tasks, ExecutionContext ctx) {
        try (var scope = StructuredTaskScope.open(Joiner.awaitAll())) {
            List<Subtask<Void>> subs = new ArrayList<>(tasks.size());
            for (Callable<Void> task : tasks) {
                ctx.throwIfCancelledOrExpired();
                subs.add(scope.fork(task));
            }
            scope.join();
            // Lazy failure list — zero allocation on the success path. Allocated only on
            // first encountered failure.
            List<Throwable> failures = null;
            for (Subtask<Void> sub : subs) {
                if (sub.state() == Subtask.State.FAILED) {
                    if (failures == null) {
                        failures = new ArrayList<>();
                    }
                    failures.add(sub.exception());
                }
            }
            if (failures == null) {
                return DispatchResult.success(null);
            }
            return DispatchResult.partial(null, failures);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return DispatchResult.failure(FlowCancellationException.CANCELLED);
        } catch (Throwable t) {
            return classify(t, ctx, ErrorPolicy.collectFailures());
        }
    }

    private static DispatchResult<Void> ignoreFailures(
            List<? extends Callable<Void>> tasks,
            ExecutionContext ctx,
            ErrorPolicy.IgnoreFailures policy) {
        // Run as collectFailures and post-filter: any failure matching the
        // predicate is dropped; otherwise behave like FailFast (the first
        // non-matching failure becomes the dispatch failure).
        try (var scope = StructuredTaskScope.open(Joiner.awaitAll())) {
            List<Subtask<Void>> subs = new ArrayList<>(tasks.size());
            for (Callable<Void> task : tasks) {
                ctx.throwIfCancelledOrExpired();
                subs.add(scope.fork(task));
            }
            scope.join();
            // Lazy non-ignored failures list — zero allocation when every failure matches
            // the ignore predicate (or no failure happens).
            List<Throwable> nonIgnored = null;
            for (Subtask<Void> sub : subs) {
                if (sub.state() == Subtask.State.FAILED) {
                    Throwable t = sub.exception();
                    if (!policy.predicate().test(t)) {
                        if (nonIgnored == null) {
                            nonIgnored = new ArrayList<>();
                        }
                        nonIgnored.add(t);
                    }
                }
            }
            if (nonIgnored == null) {
                return DispatchResult.success(null);
            }
            if (nonIgnored.size() == 1) {
                return classify(nonIgnored.getFirst(), ctx, policy);
            }
            return classify(new FlowError.Aggregated(nonIgnored), ctx, policy);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return DispatchResult.failure(FlowCancellationException.CANCELLED);
        } catch (Throwable t) {
            return classify(t, ctx, policy);
        }
    }

    private static DispatchResult<Void> isolate(
            List<? extends Callable<Void>> tasks,
            ExecutionContext ctx,
            ErrorPolicy.IsolatePerBoundary policy) {
        // Run inner in its own sub-scope; never propagate failures
        // upward — collect them as PartialFailure so the outer dispatch
        // observes a successful boundary completion.
        DispatchResult<Void> inner = fanOut(tasks, ctx, policy.inner());
        return switch (inner) {
            case DispatchResult.Success<Void> _        -> DispatchResult.success(null);
            case DispatchResult.Failure<Void> f        -> DispatchResult.partial(null, List.of(f.cause()));
            case DispatchResult.PartialFailure<Void> p -> DispatchResult.partial(null, p.failures());
            // Durable hand-off inside an isolate boundary
            // is treated as a clean success: the message has been
            // queued and any subsequent failure is observable through
            // the outbox, not through this isolated dispatch.
            case DispatchResult.Accepted<Void> _ -> DispatchResult.success(null);
        };
    }

    // ------------------------------------------------------------------
    // Throwable classification.
    // ------------------------------------------------------------------

    /**
     * Classify a {@link Throwable} into the {@link FlowError} taxonomy:
     *
     * <ul>
     * <li>{@link FlowError.Domain} → {@link DispatchResult.Failure} verbatim (no wrapping).
     * <li>{@link FlowError} (Technical / Aggregated) → returned as-is.
     * <li>{@link FlowCancellationException} / {@link FlowDeadlineExceededException} → {@link
     * DispatchResult.Failure} verbatim.
     * <li>Anything else → wrapped in {@link FlowError.Technical} carrying {@code ctx}.
     * </ul>
     */
    public static <T> DispatchResult<T> classify(
            Throwable t, ExecutionContext ctx, ErrorPolicy policy) {
        Objects.requireNonNull(t, "t");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(policy, "policy");
        // IgnoreFailures predicate: at the single-handler level the failure still surfaces
        // as Failure because no typed Success value is available. Fan-out callers that want
        // per-handler ignore semantics must use an aggregate policy.
        return switch (t) {
            case FlowError _,FlowCancellationException _,FlowDeadlineExceededException _ ->
                 DispatchResult.failure(t);
            default                                                                      -> DispatchResult.failure(new FlowError.Technical(
                    t, ctx));
        };
    }

    /**
     * Internal carrier for checked exceptions caught inside the {@code FlowScope.getWithContext(...)}
     * closure so callers can reach the original cause for classification.
     */
    private static final class HandlerCheckedException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        HandlerCheckedException(Throwable cause) {
            // Stack-traceless wrapper — gets unwrapped immediately by classify(); the wrapper's
            // trace would only point to the lambda boundary. Suppression chain stays active.
            super(
                  cause == null ? null : cause.toString(),
                  cause,
                  /* enableSuppression= */ true,
                  /* writableStackTrace= */ false);
        }
    }
}
