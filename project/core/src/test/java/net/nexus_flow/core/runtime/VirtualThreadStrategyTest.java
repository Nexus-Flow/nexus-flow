package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import org.junit.jupiter.api.Test;

/**
 * Dispatch via {@link ExecutionStrategy.VirtualThread} in asynchronous in-memory mode.
 *
 * <p>Pins three contracts:
 *
 * <ul>
 * <li>Tasks are submitted to the provided executor — not run on the caller thread.
 * <li>Pre-cancelled / expired contexts surface {@link FlowCancellationException} / cancellation
 * BEFORE the task is published.
 * <li>Exceptions thrown by the task are unwrapped from {@link
 * java.util.concurrent.ExecutionException} so callers see the original cause verbatim.
 * </ul>
 */
class VirtualThreadStrategyTest {

    @Test
    void runCallable_executesOnSubmittedExecutor_notCallerThread() throws Exception {
        try (ExecutorService es = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutionStrategy.VirtualThread strategy = new ExecutionStrategy.VirtualThread(es);
            Thread                          caller   = Thread.currentThread();
            AtomicReference<Thread>         ran      = new AtomicReference<>();
            String                          result   =
                    strategy.run(
                                 () -> {
                                     ran.set(Thread.currentThread());
                                     return "ok";
                                 },
                                 ExecutionContext.root());
            assertEquals("ok", result);
            assertNotSame(
                          caller, ran.get(), "VirtualThread strategy must run the task off the caller thread");
            assertTrue(
                       ran.get().isVirtual(),
                       "Task must run on a virtual thread carried by the provided executor");
        }
    }

    @Test
    void runCallable_bindsFlowScope_insideSubmittedTask() throws Exception {
        try (ExecutorService es = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutionStrategy.VirtualThread strategy = new ExecutionStrategy.VirtualThread(es);
            ExecutionContext                ctx      = ExecutionContext.root();
            ExecutionContext                bound    = strategy.run(FlowScope::requireCurrent, ctx);
            assertSame(
                       ctx, bound, "VirtualThread strategy must bind the supplied ctx as the current FlowScope");
        }
    }

    @Test
    void runRunnable_isFireAndForget_andHonoursExecutor() throws InterruptedException {
        try (ExecutorService es = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutionStrategy.VirtualThread strategy   = new ExecutionStrategy.VirtualThread(es);
            CountDownLatch                  ran        = new CountDownLatch(1);
            AtomicBoolean                   wasVirtual = new AtomicBoolean();
            long                            t0         = System.nanoTime();
            strategy.run(
                         () -> {
                             wasVirtual.set(Thread.currentThread().isVirtual());
                             ran.countDown();
                         },
                         ExecutionContext.root());
            long submitMs = (System.nanoTime() - t0) / 1_000_000L;
            // Fire-and-forget: the run(...) call returns quickly even
            // if the task blocks. We just need the task to actually
            // execute on the executor.
            assertTrue(
                       submitMs < 1_000L,
                       "run(Runnable) must return before the task finishes; took " + submitMs + "ms");
            assertTrue(ran.await(2, TimeUnit.SECONDS), "submitted Runnable must run on the executor");
            assertTrue(wasVirtual.get(), "submitted Runnable must run on a virtual thread");
        }
    }

    @Test
    void runCallable_preCancelledContext_throwsFlowCancellationException_beforeSubmit() {
        try (ExecutorService es = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutionStrategy.VirtualThread strategy = new ExecutionStrategy.VirtualThread(es);
            ExecutionContext                ctx      = ExecutionContext.root();
            ctx.cancellation().cancel();
            AtomicBoolean taskRan = new AtomicBoolean(false);
            assertThrows(
                         FlowCancellationException.class,
                         () -> strategy.run(
                                            () -> {
                                                taskRan.set(true);
                                                return "x";
                                            },
                                            ctx));
            assertFalse(
                        taskRan.get(), "Pre-cancelled ctx must short-circuit BEFORE the task is submitted");
        }
    }

    @Test
    void runCallable_unwrapsExecutionException_andPropagatesCauseVerbatim() {
        try (ExecutorService es = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutionStrategy.VirtualThread strategy = new ExecutionStrategy.VirtualThread(es);
            // The cause must be exposed verbatim — no ExecutionException wrapping.
            IllegalStateException thrown =
                    assertThrows(
                                 IllegalStateException.class,
                                 () -> strategy.run(
                                                    () -> {
                                                        throw new IllegalStateException("inside-vt");
                                                    },
                                                    ExecutionContext.root()));
            assertEquals("inside-vt", thrown.getMessage());
        }
    }

    @Test
    void rejectsNullArguments() {
        try (ExecutorService es = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThrows(NullPointerException.class, () -> new ExecutionStrategy.VirtualThread(null));
            ExecutionStrategy.VirtualThread strategy = new ExecutionStrategy.VirtualThread(es);
            ExecutionContext                ctx      = ExecutionContext.root();
            assertThrows(
                         NullPointerException.class,
                         () -> strategy.run((java.util.concurrent.Callable<Object>) null, ctx));
            assertThrows(NullPointerException.class, () -> strategy.run(() -> "x", null));
            assertThrows(NullPointerException.class, () -> strategy.run((Runnable) null, ctx));
        }
    }
}
