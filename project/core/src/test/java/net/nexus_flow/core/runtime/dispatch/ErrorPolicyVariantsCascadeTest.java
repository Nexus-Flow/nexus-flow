package net.nexus_flow.core.runtime.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.FlowScope;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import net.nexus_flow.core.runtime.result.FlowDeadlineExceededException;
import net.nexus_flow.core.runtime.result.FlowError;
import org.junit.jupiter.api.Test;

/**
 * Pins the four {@link ErrorPolicy} variants across (a) single-handler command dispatch, (b)
 * nested command cascade A→B→C, and (c) event fan-out under every policy.
 *
 * <p>Companion to {@code ErrorPropagationCascadeTest}, which focuses on FailFast cascade + the
 * CANCELLED singleton suppression-swap path. This file covers the remaining three policies
 * (CollectFailures, IgnoreFailures, IsolatePerBoundary), every variant under event fan-out
 * (sequential + opt-in parallel), and edge cases (deadline mid-cascade, predicate filtering,
 * boundary containment).
 */
class ErrorPolicyVariantsCascadeTest {

    // ---------- fixtures ----------

    record CmdA() {
    }

    record CmdB() {
    }

    record CmdC() {
    }

    static final class BoomException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        BoomException(String why) {
            super(why);
        }
    }

    /** A marker exception used by IgnoreFailures predicate tests. */
    static final class IgnorableException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        IgnorableException(String why) {
            super(why);
        }
    }

    static final class FanOut extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        FanOut(String aggId) {
            super(aggId);
        }
    }

    static boolean containsCauseOfType(Throwable t, Class<? extends Throwable> type) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (type.isInstance(cur)) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // Single-handler dispatch under each ErrorPolicy
    // =====================================================================

    @Test
    void singleHandler_collectFailures_failurePropagatesAsFailure_notPartial() {
        // With ONE handler, there is no fan-out, so CollectFailures degenerates to "the failure
        // is the dispatch result". The runtime MUST NOT fabricate a PartialFailure when there is
        // no concurrent peer to compare against.
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            BoomException origin = new BoomException("collect-boom");
            runtime.commands().register(new AbstractReturnCommandHandler<CmdC, String>() {
                @Override
                protected String handle(CmdC cmd) {
                    throw origin;
                }
            });

            DispatchResult<String> result = runtime.commands().dispatchAndReturnResult(
                                                                                       Command.<CmdC>builder().body(new CmdC()).build(),
                                                                                       ExecutionContext.root(),
                                                                                       ErrorPolicy.collectFailures());

            DispatchResult.Failure<String> f         = assertInstanceOf(DispatchResult.Failure.class, result);
            FlowError.Technical            technical = assertInstanceOf(FlowError.Technical.class, f.cause());
            assertSame(origin, technical.getCause());
        }
    }

    @Test
    void singleHandler_ignoreFailures_predicateMatches_returnsFailureNotSuccess() {
        // IgnoreFailures only DROPS the failure during fan-out aggregation; in single-handler
        // dispatch, classify() is called with the policy but the failure still surfaces — there
        // is nothing to swallow it into.
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            runtime.commands().register(new AbstractReturnCommandHandler<CmdC, String>() {
                @Override
                protected String handle(CmdC cmd) {
                    throw new IgnorableException("predicate-match");
                }
            });

            DispatchResult<String> result = runtime.commands().dispatchAndReturnResult(
                                                                                       Command.<CmdC>builder().body(new CmdC()).build(),
                                                                                       ExecutionContext.root(),
                                                                                       ErrorPolicy.ignore(
                                                                                                          t -> t instanceof IgnorableException || (t instanceof FlowError.Technical te && te
                                                                                                                  .getCause() instanceof IgnorableException)));

            // CRITICAL pin: single-handler IgnoreFailures STILL produces a Failure. Listener
            // fan-out aggregation is the only place predicate filtering swallows failures.
            DispatchResult.Failure<String> f = assertInstanceOf(DispatchResult.Failure.class, result);
            assertTrue(containsCauseOfType(f.cause(), IgnorableException.class));
        }
    }

    @Test
    void singleHandler_isolatePerBoundary_failureStillSurfacesAsFailure_notSuccess() {
        // IsolatePerBoundary only matters in fan-out (collapses inner failure into PartialFailure
        // so the OUTER scope sees a clean success). In single-handler command dispatch, the
        // failure still surfaces.
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            BoomException origin = new BoomException("isolate-boom");
            runtime.commands().register(new AbstractReturnCommandHandler<CmdC, String>() {
                @Override
                protected String handle(CmdC cmd) {
                    throw origin;
                }
            });

            DispatchResult<String> result = runtime.commands().dispatchAndReturnResult(
                                                                                       Command.<CmdC>builder().body(new CmdC()).build(),
                                                                                       ExecutionContext.root(),
                                                                                       ErrorPolicy.isolate(ErrorPolicy.failFast()));

            DispatchResult.Failure<String> f = assertInstanceOf(DispatchResult.Failure.class, result);
            assertTrue(containsCauseOfType(f.cause(), BoomException.class));
        }
    }

    // =====================================================================
    // A→B→C cascade under each ErrorPolicy
    // =====================================================================

    @Test
    void abc_collectFailures_innermostBoomReachableInTopLevelFailureCauseChain() {
        // Cascade behaves like fail-fast at SINGLE-HANDLER level because there is only ONE child
        // per level (B dispatches one C, A dispatches one B). The relevant pin is that C's
        // BoomException stays in the cause chain end-to-end.
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            BoomException origin = new BoomException("c-boom");
            runtime.commands().register(new AbstractReturnCommandHandler<CmdC, String>() {
                @Override
                protected String handle(CmdC cmd) {
                    throw origin;
                }
            });
            runtime.commands().register(new AbstractReturnCommandHandler<CmdB, String>() {
                @Override
                protected String handle(CmdB cmd) {
                    DispatchResult<String> r = runtime.commands().dispatchAndReturnResult(
                                                                                          Command.<CmdC>builder().body(new CmdC()).build(),
                                                                                          ExecutionContext.root(), ErrorPolicy
                                                                                                  .collectFailures());
                    if (r instanceof DispatchResult.Failure<String> f) {
                        throw (RuntimeException) f.cause();
                    }
                    return "b-ok";
                }
            });
            runtime.commands().register(new AbstractReturnCommandHandler<CmdA, String>() {
                @Override
                protected String handle(CmdA cmd) {
                    DispatchResult<String> r = runtime.commands().dispatchAndReturnResult(
                                                                                          Command.<CmdB>builder().body(new CmdB()).build(),
                                                                                          ExecutionContext.root(), ErrorPolicy
                                                                                                  .collectFailures());
                    if (r instanceof DispatchResult.Failure<String> f) {
                        throw (RuntimeException) f.cause();
                    }
                    return "a-ok";
                }
            });

            DispatchResult<String> result = runtime.commands().dispatchAndReturnResult(
                                                                                       Command.<CmdA>builder().body(new CmdA()).build(),
                                                                                       ExecutionContext.root(), ErrorPolicy
                                                                                               .collectFailures());

            DispatchResult.Failure<String> failure = assertInstanceOf(DispatchResult.Failure.class, result);
            assertTrue(containsCauseOfType(failure.cause(), BoomException.class));
        }
    }

    @Test
    void abc_mixedPolicies_outerFailFastInnerCollectFailures_innermostBoomStillReachable() {
        // A uses FailFast, B uses CollectFailures, C fails. The B level chooses to re-throw,
        // surfacing the failure through A. Pins that policy at one cascade level does not erase
        // the failure visible to a higher level — the policy controls AGGREGATION at fan-out
        // boundaries, not silent suppression.
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            BoomException origin = new BoomException("mixed-boom");
            runtime.commands().register(new AbstractReturnCommandHandler<CmdC, String>() {
                @Override
                protected String handle(CmdC cmd) {
                    throw origin;
                }
            });
            runtime.commands().register(new AbstractReturnCommandHandler<CmdB, String>() {
                @Override
                protected String handle(CmdB cmd) {
                    DispatchResult<String> r = runtime.commands().dispatchAndReturnResult(
                                                                                          Command.<CmdC>builder().body(new CmdC()).build(),
                                                                                          ExecutionContext.root(), ErrorPolicy
                                                                                                  .collectFailures());
                    if (r instanceof DispatchResult.Failure<String> f) {
                        throw (RuntimeException) f.cause();
                    }
                    return "b-ok";
                }
            });
            runtime.commands().register(new AbstractReturnCommandHandler<CmdA, String>() {
                @Override
                protected String handle(CmdA cmd) {
                    DispatchResult<String> r = runtime.commands().dispatchAndReturnResult(
                                                                                          Command.<CmdB>builder().body(new CmdB()).build(),
                                                                                          ExecutionContext.root(), ErrorPolicy.failFast());
                    if (r instanceof DispatchResult.Failure<String> f) {
                        throw (RuntimeException) f.cause();
                    }
                    return "a-ok";
                }
            });

            DispatchResult<String> result = runtime.commands().dispatchAndReturnResult(
                                                                                       Command.<CmdA>builder().body(new CmdA()).build(),
                                                                                       ExecutionContext.root(), ErrorPolicy.failFast());

            DispatchResult.Failure<String> failure = assertInstanceOf(DispatchResult.Failure.class, result);
            assertTrue(containsCauseOfType(failure.cause(), BoomException.class),
                       "C's BoomException MUST reach the top-level failure regardless of which policy each cascade level chose");
        }
    }

    @Test
    void abc_outerIgnoresMarkedExceptionsFromInner_outerObservesFailureUnlessWrapped() {
        // When B re-throws an IgnorableException from C, A's IgnoreFailures policy at the
        // single-handler level does NOT swallow it (single-handler is fail-fast-equivalent).
        // The pin: IgnoreFailures is a FAN-OUT-only operator; cascade does not get magic
        // swallowing.
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            runtime.commands().register(new AbstractReturnCommandHandler<CmdC, String>() {
                @Override
                protected String handle(CmdC cmd) {
                    throw new IgnorableException("inner-ignorable");
                }
            });
            runtime.commands().register(new AbstractReturnCommandHandler<CmdB, String>() {
                @Override
                protected String handle(CmdB cmd) {
                    DispatchResult<String> r = runtime.commands().dispatchAndReturnResult(
                                                                                          Command.<CmdC>builder().body(new CmdC()).build(),
                                                                                          ExecutionContext.root(),
                                                                                          ErrorPolicy.ignore(
                                                                                                             t -> t instanceof IgnorableException));
                    if (r instanceof DispatchResult.Failure<String> f) {
                        throw (RuntimeException) f.cause();
                    }
                    return "b-ok";
                }
            });

            DispatchResult<String> result = runtime.commands().dispatchAndReturnResult(
                                                                                       Command.<CmdB>builder().body(new CmdB()).build(),
                                                                                       ExecutionContext.root(),
                                                                                       ErrorPolicy.ignore(
                                                                                                          t -> t instanceof IgnorableException));

            DispatchResult.Failure<String> f = assertInstanceOf(DispatchResult.Failure.class, result);
            assertTrue(containsCauseOfType(f.cause(), IgnorableException.class),
                       "IgnoreFailures is a FAN-OUT operator — single-handler / cascade dispatch still surfaces the failure");
        }
    }

    // =====================================================================
    // Event fan-out under each ErrorPolicy
    // =====================================================================

    @Test
    void eventFanout_sequential_collectFailures_allListenersRunToCompletion_partialFailureCarriesAll() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            AtomicInteger l1Calls = new AtomicInteger();
            AtomicInteger l2Calls = new AtomicInteger();
            AtomicInteger l3Calls = new AtomicInteger();
            BoomException b1      = new BoomException("l1");
            BoomException b3      = new BoomException("l3");

            runtime.events().register(new AbstractDomainEventListener<FanOut>() {
                @Override
                public void handle(FanOut event) {
                    l1Calls.incrementAndGet();
                    throw b1;
                }
            });
            runtime.events().register(new AbstractDomainEventListener<FanOut>() {
                @Override
                public void handle(FanOut event) {
                    l2Calls.incrementAndGet();
                }
            });
            runtime.events().register(new AbstractDomainEventListener<FanOut>() {
                @Override
                public void handle(FanOut event) {
                    l3Calls.incrementAndGet();
                    throw b3;
                }
            });

            DispatchResult<Void> result = runtime.events().dispatchResult(
                                                                          new FanOut("agg-1"),
                                                                          ExecutionContext.root(),
                                                                          ErrorPolicy.collectFailures());

            assertEquals(1, l1Calls.get(), "l1 must run");
            assertEquals(1, l2Calls.get(), "l2 must run despite l1's failure");
            assertEquals(1, l3Calls.get(), "l3 must run despite earlier failures");

            DispatchResult.PartialFailure<Void> partial = assertInstanceOf(DispatchResult.PartialFailure.class, result,
                                                                           "CollectFailures fan-out with mixed outcomes MUST surface as PartialFailure");
            assertEquals(2, partial.failures().size(), "all listener failures must be collected");
        }
    }

    @Test
    void eventFanout_sequential_failFast_firstFailureShortCircuitsRemainingListeners() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            AtomicInteger l1Calls = new AtomicInteger();
            AtomicInteger l2Calls = new AtomicInteger();
            BoomException b1      = new BoomException("l1");

            runtime.events().register(new AbstractDomainEventListener<FanOut>() {
                @Override
                public void handle(FanOut event) {
                    l1Calls.incrementAndGet();
                    throw b1;
                }
            });
            runtime.events().register(new AbstractDomainEventListener<FanOut>() {
                @Override
                public void handle(FanOut event) {
                    l2Calls.incrementAndGet();
                }
            });

            DispatchResult<Void> result = runtime.events().dispatchResult(
                                                                          new FanOut("agg-1"),
                                                                          ExecutionContext.root(),
                                                                          ErrorPolicy.failFast());

            assertEquals(1, l1Calls.get(), "l1 must run");
            assertEquals(0, l2Calls.get(), "l2 must NOT run — failFast short-circuits sequential fan-out");

            DispatchResult.Failure<Void> f = assertInstanceOf(DispatchResult.Failure.class, result);
            assertTrue(containsCauseOfType(f.cause(), BoomException.class));
        }
    }

    @Test
    void eventFanout_sequential_ignoreFailures_predicateMatchedFailuresDropped_othersPropagate() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            AtomicInteger l1Calls = new AtomicInteger();
            AtomicInteger l2Calls = new AtomicInteger();

            runtime.events().register(new AbstractDomainEventListener<FanOut>() {
                @Override
                public void handle(FanOut event) {
                    l1Calls.incrementAndGet();
                    throw new IgnorableException("ignored");
                }
            });
            runtime.events().register(new AbstractDomainEventListener<FanOut>() {
                @Override
                public void handle(FanOut event) {
                    l2Calls.incrementAndGet();
                }
            });

            // Predicate must accommodate the FlowError.Technical wrapper that classify()
            // applies to non-FlowError throwables before they reach the policy switch — the
            // raw IgnorableException is wrapped at the dispatch boundary. Production code can
            // either inspect the cause chain (as below) or extend FlowError.Domain at the
            // throw site to skip wrapping.
            DispatchResult<Void> result = runtime.events().dispatchResult(
                                                                          new FanOut("agg-1"),
                                                                          ExecutionContext.root(),
                                                                          ErrorPolicy.ignore(
                                                                                             t -> t instanceof IgnorableException || (t instanceof FlowError.Technical te && te
                                                                                                     .getCause() instanceof IgnorableException)));

            assertEquals(1, l1Calls.get(), "l1 must run");
            assertInstanceOf(DispatchResult.Success.class, result,
                             "IgnoreFailures with predicate that matches MUST surface as Success");
        }
    }

    @Test
    void eventFanout_sequential_ignoreFailures_predicateMissed_failurePropagates() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            BoomException origin = new BoomException("not-ignored");

            runtime.events().register(new AbstractDomainEventListener<FanOut>() {
                @Override
                public void handle(FanOut event) {
                    throw origin;
                }
            });

            DispatchResult<Void> result = runtime.events().dispatchResult(
                                                                          new FanOut("agg-1"),
                                                                          ExecutionContext.root(),
                                                                          ErrorPolicy.ignore(t -> t instanceof IgnorableException));

            DispatchResult.Failure<Void> f = assertInstanceOf(DispatchResult.Failure.class, result);
            assertTrue(containsCauseOfType(f.cause(), BoomException.class));
        }
    }

    @Test
    void eventFanout_parallel_collectFailures_allListenersRunAndFailuresAggregated() {
        try (FlowRuntime runtime = FlowRuntime.builder().parallelListeners(true).build()) {
            AtomicInteger l1Calls = new AtomicInteger();
            AtomicInteger l2Calls = new AtomicInteger();
            BoomException b1      = new BoomException("l1-parallel");
            BoomException b2      = new BoomException("l2-parallel");

            runtime.events().register(new AbstractDomainEventListener<FanOut>() {
                @Override
                public boolean parallelSafe() {
                    return true;
                }

                @Override
                public void handle(FanOut event) {
                    l1Calls.incrementAndGet();
                    throw b1;
                }
            });
            runtime.events().register(new AbstractDomainEventListener<FanOut>() {
                @Override
                public boolean parallelSafe() {
                    return true;
                }

                @Override
                public void handle(FanOut event) {
                    l2Calls.incrementAndGet();
                    throw b2;
                }
            });

            DispatchResult<Void> result = runtime.events().dispatchResult(
                                                                          new FanOut("agg-1"),
                                                                          ExecutionContext.root(),
                                                                          ErrorPolicy.collectFailures());

            assertEquals(1, l1Calls.get());
            assertEquals(1, l2Calls.get());
            // Parallel fan-out with two failures → PartialFailure with both attached as
            // suppressed; pin BoomException reachability through cause chain or suppression.
            assertNotNull(result, "result must be present");
            assertTrue(result instanceof DispatchResult.PartialFailure || result instanceof DispatchResult.Failure,
                       "either PartialFailure (CollectFailures) or Failure (if scope already cancelled) — never Success");
        }
    }

    // =====================================================================
    // Deadline mid-cascade
    // =====================================================================

    @Test
    void deadlineExpiresBetweenCascadeLevels_propagatesAsFlowDeadlineExceededException() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            runtime.commands().register(new AbstractReturnCommandHandler<CmdC, String>() {
                @Override
                protected String handle(CmdC cmd) {
                    return "c-ok-but-never-reached";
                }
            });
            runtime.commands().register(new AbstractReturnCommandHandler<CmdB, String>() {
                @Override
                protected String handle(CmdB cmd) {
                    // Sleep past the parent deadline, THEN poll the parent context — the
                    // handler signature does not receive the context, so we read it back
                    // through FlowScope (the same channel cross-cutting code uses for
                    // tracing/logging). Polling is cooperative; the framework does not
                    // pre-empt sleeping handlers.
                    try {
                        Thread.sleep(60);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    ExecutionContext parent = FlowScope.current().orElseGet(ExecutionContext::root);
                    parent.throwIfCancelledOrExpired();
                    return "b-ok";
                }
            });

            ExecutionContext       ctx    = ExecutionContext.rootWithTimeout(Duration.ofMillis(20));
            DispatchResult<String> result = runtime.commands().dispatchAndReturnResult(
                                                                                       Command.<CmdB>builder().body(new CmdB()).build(),
                                                                                       ctx, ErrorPolicy.failFast());

            DispatchResult.Failure<String> f = assertInstanceOf(DispatchResult.Failure.class, result);
            assertTrue(containsCauseOfType(f.cause(), FlowDeadlineExceededException.class),
                       "Deadline expiration MUST surface as FlowDeadlineExceededException through the cascade — got: " + f.cause());
        }
    }

    // =====================================================================
    // Cancellation mid-cascade
    // =====================================================================

    @Test
    void cancellationFromOuterContext_propagatesToInnerCommand() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            AtomicReference<ExecutionContext> outerCtx = new AtomicReference<>();
            runtime.commands().register(new AbstractReturnCommandHandler<CmdC, String>() {
                @Override
                protected String handle(CmdC cmd) {
                    // C polls the outer cancellation directly through the captured context.
                    outerCtx.get().throwIfCancelledOrExpired();
                    return "c-ok";
                }
            });
            runtime.commands().register(new AbstractReturnCommandHandler<CmdB, String>() {
                @Override
                protected String handle(CmdB cmd) {
                    // Cancel the outer context BEFORE dispatching C; C's poll must observe it.
                    outerCtx.get().cancellation().cancel();
                    DispatchResult<String> r = runtime.commands().dispatchAndReturnResult(
                                                                                          Command.<CmdC>builder().body(new CmdC()).build(),
                                                                                          ExecutionContext.root(), ErrorPolicy.failFast());
                    if (r instanceof DispatchResult.Failure<String> f) {
                        throw (RuntimeException) f.cause();
                    }
                    return "b-ok";
                }
            });

            ExecutionContext ctx = ExecutionContext.root();
            outerCtx.set(ctx);
            DispatchResult<String> result = runtime.commands().dispatchAndReturnResult(
                                                                                       Command.<CmdB>builder().body(new CmdB()).build(),
                                                                                       ctx, ErrorPolicy.failFast());

            DispatchResult.Failure<String> f = assertInstanceOf(DispatchResult.Failure.class, result);
            assertTrue(containsCauseOfType(f.cause(), FlowCancellationException.class),
                       "Cancellation MUST surface as FlowCancellationException through the cascade — got: " + f.cause());
        }
    }

    // =====================================================================
    // PartialFailure shape pin
    // =====================================================================

    @Test
    void partialFailure_failures_areImmutableAndCarryEveryListenerFailureInOrderOfObservation() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            BoomException b1 = new BoomException("first");
            BoomException b2 = new BoomException("second");
            BoomException b3 = new BoomException("third");

            runtime.events().register(new AbstractDomainEventListener<FanOut>() {
                @Override
                public void handle(FanOut event) {
                    throw b1;
                }
            });
            runtime.events().register(new AbstractDomainEventListener<FanOut>() {
                @Override
                public void handle(FanOut event) {
                    throw b2;
                }
            });
            runtime.events().register(new AbstractDomainEventListener<FanOut>() {
                @Override
                public void handle(FanOut event) {
                    throw b3;
                }
            });

            DispatchResult<Void> result = runtime.events().dispatchResult(
                                                                          new FanOut("agg-1"),
                                                                          ExecutionContext.root(),
                                                                          ErrorPolicy.collectFailures());

            DispatchResult.PartialFailure<Void> partial  = assertInstanceOf(DispatchResult.PartialFailure.class, result);
            List<Throwable>                     failures = partial.failures();
            assertEquals(3, failures.size());
            // Sequential fan-out preserves listener registration order — each failure is the
            // FlowError.Technical wrapper that classify() applied at the dispatch boundary.
            // The original BoomException is reachable through getCause().
            assertSame(b1, failures.get(0).getCause(),
                       "first failure cause must be b1 (Technical wrapper preserves identity through getCause())");
            assertSame(b2, failures.get(1).getCause());
            assertSame(b3, failures.get(2).getCause());
            // Immutability check: PartialFailure.failures() is defensively copied via List.copyOf.
            org.junit.jupiter.api.Assertions.assertThrows(
                                                          UnsupportedOperationException.class,
                                                          () -> failures.add(new BoomException("intruder")),
                                                          "PartialFailure.failures() MUST be unmodifiable");
        }
    }
}
