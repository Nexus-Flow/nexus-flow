package net.nexus_flow.core.cqrs.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowError;
import org.junit.jupiter.api.Test;

/**
 * Audit §6 T3. Parent-command → nested-command error propagation through {@link
 * CommandBus#dispatchAndReturnResult(Command, ExecutionContext, ErrorPolicy)}.
 *
 * <p>Existing error-policy tests (see {@code SyncDispatcherErrorModelTest}) drive policy variants
 * through event fan-out — the structured path the runtime exposes to user code. This test covers
 * the OTHER common shape: a command handler that itself calls {@code
 * commandBus.dispatchAndReturnResult(...)} for a nested command, and asserts:
 *
 * <ul>
 * <li>Under {@link ErrorPolicy#failFast()}, a nested-handler throw surfaces as the nested call's
 * {@link DispatchResult.Failure}. The outer handler can swallow it (returning a value) or
 * propagate by re-throwing — the runtime does NOT auto-fold nested results into the parent.
 * <li>Under {@link ErrorPolicy#collectFailures()}, the nested call still returns a non-success
 * result that the outer handler can inspect.
 * </ul>
 *
 * <p>The asymmetry vs event fan-out is intentional and pinned here: nested command calls are
 * bounded contexts inside the outer handler; the outer handler decides what to surface. This keeps
 * {@code dispatchAndReturnResult} composable without leaking implicit error-folding rules across
 * user-code boundaries.
 *
 * <p>Both handlers use {@link AbstractReturnCommandHandler} because the value-returning path is the
 * only one with a {@code DispatchResult}-returning bus entry. The void path ({@code
 * AbstractNoReturnCommandHandler} + {@code dispatch(Command)}) is fire-and-forget and surfaces no
 * failures back to the caller — that asymmetry is by design (see CommandBus Javadoc).
 */
class NestedCommandFailurePropagationTest {

    record Outer() {
    }

    record Inner() {
    }

    static final class BoomException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BoomException() {
            super("nested-command-boom");
        }
    }

    /**
     * Inner handler that always throws — used to verify failure surfaces in nested DispatchResult.
     */
    static final class FailingInnerHandler extends AbstractReturnCommandHandler<Inner, String> {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        protected String handle(Inner cmd) {
            calls.incrementAndGet();
            throw new BoomException();
        }
    }

    @Test
    void nestedCommandFailure_failFast_surfacesInNestedDispatchResult_outerCanSwallow() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            FailingInnerHandler                     inner        = new FailingInnerHandler();
            AtomicReference<DispatchResult<String>> nestedResult = new AtomicReference<>();
            AtomicInteger                           outerCalls   = new AtomicInteger();

            var outerHandler =
                    new AbstractReturnCommandHandler<Outer, String>() {
                        @Override
                        protected String handle(Outer cmd) {
                            outerCalls.incrementAndGet();
                            DispatchResult<String> r =
                                    runtime
                                            .commands()
                                            .dispatchAndReturnResult(
                                                                     Command.<Inner>builder().body(new Inner()).build(),
                                                                     ExecutionContext.root(),
                                                                     ErrorPolicy.failFast());
                            nestedResult.set(r);
                            // Outer chooses to SWALLOW the nested failure and return a sentinel result.
                            if (r instanceof DispatchResult.Failure<String>) {
                                return "outer-recovered";
                            }
                            return "outer-ok";
                        }
                    };

            runtime.commands().register(outerHandler);
            runtime.commands().register(inner);

            DispatchResult<String> outerResult =
                    runtime
                            .commands()
                            .dispatchAndReturnResult(
                                                     Command.<Outer>builder().body(new Outer()).build(),
                                                     ExecutionContext.root(),
                                                     ErrorPolicy.failFast());

            assertEquals(1, outerCalls.get(), "outer handler ran exactly once");
            assertEquals(1, inner.calls.get(), "inner handler ran exactly once");
            DispatchResult<String> r = nestedResult.get();
            assertNotNull(r);
            DispatchResult.Failure<String> nestedFailure =
                    assertInstanceOf(
                                     DispatchResult.Failure.class,
                                     r,
                                     "nested call MUST return Failure when handler throws under failFast");
            assertNotNull(nestedFailure.cause(), "Failure must carry a cause");

            // Outer is Success because the outer handler explicitly swallowed the nested failure.
            DispatchResult.Success<String> outerSuccess =
                    assertInstanceOf(
                                     DispatchResult.Success.class,
                                     outerResult,
                                     "outer handler explicitly swallowed nested failure — outer must be Success. "
                                             + "This pins the contract that nested results do NOT auto-fold into the parent.");
            assertEquals("outer-recovered", outerSuccess.value());
        }
    }

    @Test
    void nestedCommandFailure_outerHandlerCanReThrow_surfacesAsOuterFailure() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            FailingInnerHandler inner = new FailingInnerHandler();

            var outerHandler =
                    new AbstractReturnCommandHandler<Outer, String>() {
                        @Override
                        protected String handle(Outer cmd) {
                            DispatchResult<String> r =
                                    runtime
                                            .commands()
                                            .dispatchAndReturnResult(
                                                                     Command.<Inner>builder().body(new Inner()).build(),
                                                                     ExecutionContext.root(),
                                                                     ErrorPolicy.failFast());
                            if (r instanceof DispatchResult.Failure<String> f) {
                                Throwable cause = f.cause();
                                // Outer chooses to PROPAGATE: re-throw as a runtime exception.
                                if (cause instanceof RuntimeException re) {
                                    throw re;
                                }
                                throw new RuntimeException(cause);
                            }
                            return "outer-ok";
                        }
                    };
            runtime.commands().register(outerHandler);
            runtime.commands().register(inner);

            DispatchResult<String> outerResult =
                    runtime
                            .commands()
                            .dispatchAndReturnResult(
                                                     Command.<Outer>builder().body(new Outer()).build(),
                                                     ExecutionContext.root(),
                                                     ErrorPolicy.failFast());

            assertEquals(1, inner.calls.get(), "inner handler ran exactly once");
            DispatchResult.Failure<String> outerFailure =
                    assertInstanceOf(DispatchResult.Failure.class, outerResult);
            assertNotNull(outerFailure.cause());
            // The classifier may wrap non-domain throwables into FlowError.Technical; the original
            // BoomException must be reachable in the cause chain.
            assertTrue(
                       containsCauseOfType(outerFailure.cause(), BoomException.class) || (outerFailure
                               .cause() instanceof FlowError.Technical t && containsCauseOfType(t, BoomException.class)),
                       "outer Failure cause chain MUST contain the original BoomException; got "
                               + outerFailure.cause());
        }
    }

    @Test
    void nestedCommandFailure_underCollectFailures_returnsNonSuccessFromNestedCall() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            FailingInnerHandler                     inner        = new FailingInnerHandler();
            AtomicReference<DispatchResult<String>> nestedResult = new AtomicReference<>();

            var outerHandler =
                    new AbstractReturnCommandHandler<Outer, String>() {
                        @Override
                        protected String handle(Outer cmd) {
                            nestedResult.set(
                                             runtime
                                                     .commands()
                                                     .dispatchAndReturnResult(
                                                                              Command.<Inner>builder().body(new Inner()).build(),
                                                                              ExecutionContext.root(),
                                                                              ErrorPolicy.collectFailures()));
                            return "outer-ok";
                        }
                    };
            runtime.commands().register(outerHandler);
            runtime.commands().register(inner);

            runtime
                    .commands()
                    .dispatchAndReturnResult(
                                             Command.<Outer>builder().body(new Outer()).build(),
                                             ExecutionContext.root(),
                                             ErrorPolicy.collectFailures());

            DispatchResult<String> r = nestedResult.get();
            assertNotNull(r);
            assertTrue(
                       r instanceof DispatchResult.Failure<String> || r instanceof DispatchResult.PartialFailure<String>,
                       "nested result under collectFailures must be Failure or PartialFailure; got "
                               + r.getClass().getName());
        }
    }

    private static boolean containsCauseOfType(Throwable t, Class<? extends Throwable> type) {
        Throwable cur = t;
        while (cur != null) {
            if (type.isInstance(cur))
                return true;
            cur = cur.getCause();
        }
        return false;
    }
}
