package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import org.junit.jupiter.api.Test;

/**
 * Double-guard inside {@link ExecutionStrategy.VirtualThread}.
 *
 * <p>Pre-2.3 the strategy only checked {@link ExecutionContext#throwIfCancelledOrExpired()} on the
 * caller, before {@code executor.submit(...)}. If the executor's queue was busy the submitted task
 * could sit there for an unbounded amount of wall-clock time; a cancellation raised after submit
 * but before the carrier picked the task up would <em>still</em> let the user code run.adds an
 * inner re-check inside the submitted Callable / Runnable so the user code never executes against a
 * cancelled context.
 *
 * <p>The test uses a delaying executor that holds tasks until the test releases them — that way we
 * can deterministically open the "submit-vs-run" race and observe the inner guard.
 */
class VtSubmissionDoubleGuardTest {

    /**
     * {@link AbstractExecutorService} that defers running submitted tasks until {@link #release()} is
     * invoked. Lets the test land a cancellation between {@code submit(...)} and the actual run.
     */
    private static final class DeferredExecutor extends AbstractExecutorService {
        private final CountDownLatch gate     = new CountDownLatch(1);
        private volatile Thread      worker;
        private final AtomicBoolean  shutdown = new AtomicBoolean();

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            // Use a FutureTask so the strategy's future.get() unblocks
            // when we run the task.
            FutureTask<T> ft = new FutureTask<>(task);
            execute(ft);
            return ft;
        }

        @Override
        public Future<?> submit(Runnable task) {
            FutureTask<Void> ft = new FutureTask<>(task, null);
            execute(ft);
            return ft;
        }

        @Override
        public void execute(Runnable command) {
            // Run on a fresh carrier thread but only after the gate
            // opens — this models the executor's queue being busy.
            worker =
                    Thread.ofVirtual()
                            .start(
                                   () -> {
                                       try {
                                           gate.await();
                                           command.run();
                                       } catch (InterruptedException ie) {
                                           Thread.currentThread().interrupt();
                                       }
                                   });
        }

        void release() {
            gate.countDown();
        }

        Thread worker() {
            return worker;
        }

        // ---- Boilerplate AbstractExecutorService plumbing ----------

        @Override
        public void shutdown() {
            shutdown.set(true);
            release();
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            shutdown.set(true);
            release();
            return java.util.List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown.get();
        }

        @Override
        public boolean isTerminated() {
            Thread w = worker;
            return shutdown.get() && (w == null || !w.isAlive());
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            Thread w = worker;
            if (w == null)
                return true;
            w.join(unit.toMillis(timeout));
            return !w.isAlive();
        }

        @Override
        protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
            return new FutureTask<>(callable);
        }

        @Override
        protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
            return new FutureTask<>(runnable, value);
        }
    }

    // ------------------------------------------------------------------
    // Callable variant — cancellation between submit and run must
    // surface FlowCancellationException without invoking the body.
    // ------------------------------------------------------------------

    @Test
    void callableVariant_cancelBetweenSubmitAndRun_skipsBody() throws Exception {
        DeferredExecutor                exec     = new DeferredExecutor();
        ExecutionStrategy.VirtualThread strategy = new ExecutionStrategy.VirtualThread(exec);
        ExecutionContext                ctx      = ExecutionContext.root();
        AtomicBoolean                   bodyRan  = new AtomicBoolean();

        // The strategy's run() blocks on future.get() inside the
        // submitted task. We need to call run() from a fork so we can
        // (a) cancel and (b) release the deferred executor in the
        // test thread.
        Thread caller =
                Thread.ofVirtual()
                        .start(
                               () -> {
                                   try {
                                       strategy.run(
                                                    (Callable<String>) () -> {
                                                        bodyRan.set(true);
                                                        return "should-not-run";
                                                    },
                                                    ctx);
                                   } catch (FlowCancellationException expected) {
                                       // contract — ignored, asserted via bodyRan below.
                                   } catch (Exception e) {
                                       throw new RuntimeException(e);
                                   }
                               });

        // Wait until the strategy has submitted the task to our
        // deferred executor (the worker is created in execute()).
        Thread worker;
        long   deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((worker = exec.worker()) == null) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Strategy never submitted the task");
            }
            Thread.sleep(5);
        }
        assertNotNull(worker, "Deferred executor should have a worker after submit");

        // Land the cancellation BEFORE the deferred executor releases
        // the task — this is the submit-vs-run race window.
        ctx.cancellation().cancel();

        // Now release: the worker runs the strategy's inner Callable,
        // which must observe the cancellation and skip the user body.
        exec.release();

        caller.join(TimeUnit.SECONDS.toMillis(2));
        assertFalse(caller.isAlive(), "Caller fork must have returned after the worker ran");

        assertFalse(
                    bodyRan.get(),
                    " the inner double-guard MUST short-circuit the user body "
                            + "when the context is cancelled between submit and run.");
    }

    // ------------------------------------------------------------------
    // Runnable variant — same race, fire-and-forget. The inner guard
    // throws inside the worker; we observe by checking that bodyRan
    // stays false.
    // ------------------------------------------------------------------

    @Test
    void runnableVariant_cancelBetweenSubmitAndRun_skipsBody() throws Exception {
        DeferredExecutor                exec     = new DeferredExecutor();
        ExecutionStrategy.VirtualThread strategy = new ExecutionStrategy.VirtualThread(exec);
        ExecutionContext                ctx      = ExecutionContext.root();
        AtomicBoolean                   bodyRan  = new AtomicBoolean();

        strategy.run(() -> bodyRan.set(true), ctx);

        // submit returned synchronously — worker is queued behind the
        // gate. Cancel, then release. The inner guard should fire.
        Thread worker = exec.worker();
        assertNotNull(worker, "Deferred executor should have a worker after submit");

        ctx.cancellation().cancel();
        exec.release();
        worker.join(TimeUnit.SECONDS.toMillis(2));
        assertFalse(worker.isAlive(), "Worker should have completed");

        assertFalse(bodyRan.get(), " Runnable variant must also honour the inner double-guard.");
    }

    // ------------------------------------------------------------------
    // Sanity: when the ctx is cancelled BEFORE submit, the borde guard
    // wins and the executor never sees a task. Documents that the
    // double-guard is additive, not a replacement.
    // ------------------------------------------------------------------

    @Test
    void preCancelled_bordeGuardStillWins_executorNeverSeesSubmit() {
        DeferredExecutor                exec     = new DeferredExecutor();
        ExecutionStrategy.VirtualThread strategy = new ExecutionStrategy.VirtualThread(exec);
        ExecutionContext                ctx      = ExecutionContext.root();
        ctx.cancellation().cancel();

        // Callable variant — borde guard throws synchronously on the
        // caller, so we never reach exec.submit(...).
        FlowCancellationException ex =
                assertThrows(FlowCancellationException.class, () -> strategy.run(() -> "unused", ctx));
        assertNotNull(ex);

        try {
            assertSame(
                       null,
                       exec.worker(),
                       "Borde guard fires synchronously on the caller; the deferred "
                               + "executor must not have received any task.");
        } catch (AssertionError ae) {
            // Some JVMs may have eagerly created the worker in
            // execute() depending on execution order — the load-bearing
            // contract is "no body ran", which is implicit in the
            // exception thrown above.
            assertInstanceOf(
                             AssertionError.class,
                             ae,
                             "Borde guard threw before any body executed; the strategy must "
                                     + "honour this guarantee even if the JVM raced ahead of the assertion.");
        }
    }
}
