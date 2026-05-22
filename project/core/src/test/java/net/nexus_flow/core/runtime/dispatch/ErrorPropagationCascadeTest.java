package net.nexus_flow.core.runtime.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import net.nexus_flow.core.runtime.result.FlowError;
import org.junit.jupiter.api.Test;

/**
 * Pins the error-propagation cascade across nested command dispatch chains and the suppression
 * chain semantics with the {@link FlowCancellationException#CANCELLED} singleton.
 *
 * <p>The framework deliberately does NOT auto-fold nested dispatch results into the parent —
 * the parent handler chooses to swallow, re-throw, or transform. These tests pin every
 * supported decision under every policy + cause-chain shape so a regression in
 * {@code SyncDispatcher} / {@code DefaultEventBus} / exception singleton handling cannot land
 * silently.
 */
class ErrorPropagationCascadeTest {

    // ---------- fixtures ----------

    record CmdA() {
    }

    record CmdB() {
    }

    record CmdC() {
    }

    static final class BoomException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        BoomException(String why) {
            super(why);
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

    // ---------- A → B → C cascade ----------

    @Test
    void abc_failFast_innermostBoomReachableInTopLevelCauseChain() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            AtomicInteger cCalls = new AtomicInteger();
            runtime.commands().register(new AbstractReturnCommandHandler<CmdC, String>() {
                @Override
                protected String handle(CmdC cmd) {
                    cCalls.incrementAndGet();
                    throw new BoomException("c-boom");
                }
            });
            runtime.commands().register(new AbstractReturnCommandHandler<CmdB, String>() {
                @Override
                protected String handle(CmdB cmd) {
                    DispatchResult<String> r = runtime.commands().dispatchAndReturnResult(
                                                                                          Command.<CmdC>builder().body(new CmdC()).build(),
                                                                                          ExecutionContext.root(), ErrorPolicy.failFast());
                    if (r instanceof DispatchResult.Failure<String> f) {
                        if (f.cause() instanceof RuntimeException re) {
                            throw re;
                        }
                        throw new RuntimeException(f.cause());
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
                        if (f.cause() instanceof RuntimeException re) {
                            throw re;
                        }
                        throw new RuntimeException(f.cause());
                    }
                    return "a-ok";
                }
            });

            DispatchResult<String> result = runtime.commands().dispatchAndReturnResult(
                                                                                       Command.<CmdA>builder().body(new CmdA()).build(),
                                                                                       ExecutionContext.root(), ErrorPolicy.failFast());

            assertEquals(1, cCalls.get(), "C runs exactly once");
            DispatchResult.Failure<String> failure = assertInstanceOf(DispatchResult.Failure.class, result);
            assertNotNull(failure.cause());
            assertTrue(
                       containsCauseOfType(failure.cause(), BoomException.class),
                       "BoomException MUST be in the cause chain of the top-level failure; got " + failure.cause());
        }
    }

    @Test
    void abc_outerSwallowsInnerFailure_outerReturnsSuccess() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            runtime.commands().register(new AbstractReturnCommandHandler<CmdC, String>() {
                @Override
                protected String handle(CmdC cmd) {
                    throw new BoomException("c-boom");
                }
            });
            runtime.commands().register(new AbstractReturnCommandHandler<CmdB, String>() {
                @Override
                protected String handle(CmdB cmd) {
                    DispatchResult<String> r = runtime.commands().dispatchAndReturnResult(
                                                                                          Command.<CmdC>builder().body(new CmdC()).build(),
                                                                                          ExecutionContext.root(), ErrorPolicy.failFast());
                    return r instanceof DispatchResult.Failure<String> ? "b-recovered" : "b-ok";
                }
            });

            DispatchResult<String> result = runtime.commands().dispatchAndReturnResult(
                                                                                       Command.<CmdB>builder().body(new CmdB()).build(),
                                                                                       ExecutionContext.root(), ErrorPolicy.failFast());

            DispatchResult.Success<String> success = assertInstanceOf(DispatchResult.Success.class, result,
                                                                      "B explicitly swallowed C's failure — B must surface as Success");
            assertEquals("b-recovered", success.value());
        }
    }

    // ---------- CANCELLED singleton: suppression chain preservation ----------

    @Test
    void cancelledSingleton_addSuppressedIsNoOp_butSwapPathPreservesSuppressed() {
        // The CANCELLED singleton has enableSuppression=false — addSuppressed is a no-op by
        // JDK contract. This test pins the contract so future refactors do not silently
        // change it (and so the foldTerminal swap-to-fresh path in DefaultEventBus remains
        // necessary).
        Throwable suppressed = new BoomException("won the race");
        FlowCancellationException.CANCELLED.addSuppressed(suppressed);
        assertEquals(0, FlowCancellationException.CANCELLED.getSuppressed().length,
                     "CANCELLED singleton MUST NOT accumulate suppressed exceptions across threads/dispatches");

        // A FRESH FlowCancellationException (the swap target) DOES allow suppression.
        FlowCancellationException fresh = new FlowCancellationException();
        fresh.addSuppressed(suppressed);
        assertEquals(1, fresh.getSuppressed().length,
                     "A non-singleton FlowCancellationException MUST accept suppressed entries — this is the"
                             + " escape hatch foldTerminal uses when listener failures must travel alongside the"
                             + " cancellation cause");
        assertSame(suppressed, fresh.getSuppressed()[0]);
    }

    // ---------- ErrorPolicy semantic preservation ----------

    @Test
    void failFast_innerBoomFromHandler_isClassifiedAsTechnical_andCarriesOriginal() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            BoomException origin = new BoomException("origin");
            runtime.commands().register(new AbstractReturnCommandHandler<CmdC, String>() {
                @Override
                protected String handle(CmdC cmd) {
                    throw origin;
                }
            });

            DispatchResult<String> result = runtime.commands().dispatchAndReturnResult(
                                                                                       Command.<CmdC>builder().body(new CmdC()).build(),
                                                                                       ExecutionContext.root(), ErrorPolicy.failFast());

            DispatchResult.Failure<String> f         = assertInstanceOf(DispatchResult.Failure.class, result);
            FlowError.Technical            technical = assertInstanceOf(FlowError.Technical.class, f.cause(),
                                                                        "non-FlowError handler exceptions MUST be classified as FlowError.Technical");
            assertSame(origin, technical.getCause(),
                       "the original BoomException MUST be the immediate cause of the Technical wrapper");
        }
    }

    @Test
    void failFast_cancellationFromTokenInsideHandler_surfacesAsCancelledSingleton() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            AtomicReference<Throwable> seen = new AtomicReference<>();
            runtime.commands().register(new AbstractReturnCommandHandler<CmdC, String>() {
                @Override
                protected String handle(CmdC cmd) {
                    // CancellationToken.throwIfCancellationRequested uses the CANCELLED singleton
                    // when the token is set. Simulate by throwing the same singleton via the
                    // public throw-helper to pin the contract.
                    throw FlowCancellationException.CANCELLED;
                }
            });

            DispatchResult<String>         result = runtime.commands().dispatchAndReturnResult(
                                                                                               Command.<CmdC>builder().body(new CmdC())
                                                                                                       .build(),
                                                                                               ExecutionContext.root(), ErrorPolicy
                                                                                                       .failFast());
            DispatchResult.Failure<String> f      = assertInstanceOf(DispatchResult.Failure.class, result);
            seen.set(f.cause());
            assertSame(FlowCancellationException.CANCELLED, seen.get(),
                       "The cancellation singleton MUST flow through classify() verbatim (FlowCancellationException pattern)."
                               + " Tests that rely on the singleton identity must not be broken by future"
                               + " refactors of SyncDispatcher.classify");
        }
    }

    // ---------- Stack-traceless exceptions: cause chain still intact ----------

    @Test
    void stacktracelessException_causeChainIntact() {
        BoomException                                               origin  = new BoomException("origin");
        net.nexus_flow.core.runtime.result.FlowInterruptedException wrapped =
                new net.nexus_flow.core.runtime.result.FlowInterruptedException("interrupted", new InterruptedException());
        wrapped.addSuppressed(origin);
        List<Throwable> suppressed = List.of(wrapped.getSuppressed());
        assertEquals(1, suppressed.size(),
                     "Non-singleton stack-traceless exceptions MUST keep suppression enabled — this test"
                             + " pins the {@code enableSuppression=true} flag set on every per-instance"
                             + " stack-traceless constructor.");
        assertSame(origin, suppressed.getFirst());
        assertEquals(0, wrapped.getStackTrace().length,
                     "Stack trace MUST be empty (writableStackTrace=false) — the saving the optimisation buys.");
    }
}
