package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import org.junit.jupiter.api.Test;

/**
 * Dispatch via {@link ExecutionStrategy.Inline} in synchronous mode.
 *
 * <p>Pins the core contract (no exception wrapping) and the cancellation/deadline gates as observed
 * at the strategy boundary. The strategy MUST:
 *
 * <ul>
 * <li>run on the caller thread,
 * <li>bind the supplied {@link ExecutionContext} into {@link FlowScope#CURRENT_CONTEXT} for the
 * dynamic extent of the task,
 * <li>propagate handler exceptions VERBATIM (no {@code CompletionException}, no {@code
 *       ExecutionException}).
 * </ul>
 */
class InlineStrategyTest {

    @Test
    void runCallable_executesOnCallerThread() throws Exception {
        ExecutionStrategy.Inline strategy = new ExecutionStrategy.Inline();
        Thread                   caller   = Thread.currentThread();
        AtomicReference<Thread>  seen     = new AtomicReference<>();
        Integer                  result   =
                strategy.run(
                             () -> {
                                 seen.set(Thread.currentThread());
                                 return 42;
                             },
                             ExecutionContext.root());
        assertEquals(42, result);
        assertSame(caller, seen.get(), "Inline strategy must execute on the caller thread");
    }

    @Test
    void runRunnable_executesOnCallerThread_andBindsFlowScope() {
        ExecutionStrategy.Inline          strategy = new ExecutionStrategy.Inline();
        ExecutionContext                  ctx      = ExecutionContext.root();
        AtomicReference<ExecutionContext> bound    = new AtomicReference<>();
        strategy.run(() -> bound.set(FlowScope.requireCurrent()), ctx);
        assertSame(
                   ctx, bound.get(), "Inline strategy must bind the provided ctx as the current FlowScope");
    }

    @Test
    void runCallable_propagatesCheckedExceptions_verbatim() {
        ExecutionStrategy.Inline strategy = new ExecutionStrategy.Inline();
        // Domain errors must surface VERBATIM — no wrapping in CompletionException /
        // ExecutionException.
        Exception thrown =
                assertThrows(
                             Exception.class,
                             () -> strategy.run(
                                                () -> {
                                                    throw new IllegalStateException("boom");
                                                },
                                                ExecutionContext.root()));
        assertNotNull(thrown);
        // The thrown exception must be IllegalStateException itself,
        // not a wrapper around it.
        assertSame(IllegalStateException.class, thrown.getClass());
        assertEquals("boom", thrown.getMessage());
    }

    @Test
    void runRunnable_propagatesRuntimeExceptions_verbatim() {
        ExecutionStrategy.Inline strategy = new ExecutionStrategy.Inline();
        IllegalArgumentException thrown   =
                assertThrows(
                             IllegalArgumentException.class,
                             () -> strategy.run(
                                                () -> {
                                                    throw new IllegalArgumentException("nope");
                                                },
                                                ExecutionContext.root()));
        assertEquals("nope", thrown.getMessage());
    }

    @Test
    void runCallable_preCancelledContext_throwsFlowCancellationException() {
        // Cancellation observed at a safe point surfaces FlowCancellationException;
        // the strategy is one such safe point.
        ExecutionStrategy.Inline strategy = new ExecutionStrategy.Inline();
        ExecutionContext         ctx      = ExecutionContext.root();
        ctx.cancellation().cancel();
        AtomicReference<Boolean> ran = new AtomicReference<>(false);
        assertThrows(
                     FlowCancellationException.class,
                     () -> strategy.run(
                                        () -> {
                                            ran.set(true);
                                            return "x";
                                        },
                                        ctx));
        assertTrue(!ran.get(), "Cancelled ctx must short-circuit BEFORE running the task");
    }

    @Test
    void rejectsNullArguments() {
        ExecutionStrategy.Inline strategy = new ExecutionStrategy.Inline();
        ExecutionContext         ctx      = ExecutionContext.root();
        assertThrows(
                     NullPointerException.class,
                     () -> strategy.run((java.util.concurrent.Callable<Object>) null, ctx));
        assertThrows(NullPointerException.class, () -> strategy.run(() -> "x", null));
        assertThrows(NullPointerException.class, () -> strategy.run((Runnable) null, ctx));
    }
}
