package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import org.junit.jupiter.api.Test;

/**
 * Pins two end-to-end invariants of the cooperative-cancellation contract that worker tests touch
 * obliquely but that deserve a dedicated regression:
 *
 * <ol>
 * <li><strong>Cross-thread context identity propagation.</strong> A context bound on thread A
 * (the caller) via {@link FlowScope#carrierFor(ExecutionContext)} or {@link
 * FlowScope#runWithContext(ExecutionContext, Runnable)} is observable by thread B (a
 * platform/virtual worker thread) as the EXACT same instance through {@link
 * FlowScope#current()} — identity preserved, no copy, no rebuild.
 * <li><strong>Cooperative-cancellation latency bound.</strong> When thread A cancels the bound
 * context's token while thread B is polling {@link
 * ExecutionContext#throwIfCancelledOrExpired()} on a 20 ms cadence, thread B observes the
 * cancellation and throws {@link FlowCancellationException} within a small multiple of the
 * poll period (CI noise absorbed at 1 s).
 * </ol>
 *
 * <p>These are the foundational guarantees the worker tests ({@code
 * OutboxWorkerCancelsInFlightDispatchOnShutdownTest}, {@code
 * ScheduledCommandWorkerCancelsInFlightDispatchOnShutdownTest}, {@code
 * SagaRunnerCancelsInFlightHandlerOnShutdownTest}) all rest on. Pinning them at the {@link
 * FlowScope} layer keeps a regression visible even if every worker test happens to be commented out
 * at once.
 *
 * <p>Determinism: a {@link CountDownLatch} signals when thread B has entered its poll loop, so
 * thread A never cancels before the binding is observable. No fragile sleeps in the test body — the
 * 20 ms inside the handler is the cooperative poll cadence (handler simulating real work), not test
 * sync.
 */
class CrossThreadContextPropagationAndCancellationLatencyTest {

    private static ExecutionContext freshCtx(CancellationToken token) {
        return new ExecutionContext(
                MessageId.random(),
                TraceId.random(),
                CorrelationId.random(),
                CausationId.ROOT,
                null,
                null,
                null,
                token,
                java.util.Map.of("test.marker", "cross-thread-propagation"));
    }

    @Test
    void contextBoundOnThreadA_isObservableAsSameInstanceOnThreadB() throws Exception {
        ExecutionContext                  outerCtx         = freshCtx(CancellationToken.create());
        AtomicReference<ExecutionContext> observedOnWorker = new AtomicReference<>();
        CountDownLatch                    workerStarted    = new CountDownLatch(1);
        CountDownLatch                    testFinished     = new CountDownLatch(1);

        Thread workerThread =
                new Thread(
                        () -> {
                            FlowScope.runWithContext(
                                                     outerCtx,
                                                     () -> {
                                                         observedOnWorker.set(FlowScope.current().orElseThrow());
                                                         workerStarted.countDown();
                                                         try {
                                                             testFinished.await();
                                                         } catch (InterruptedException _) {
                                                             Thread.currentThread().interrupt();
                                                         }
                                                     });
                        },
                        "cross-thread-context-test-worker");
        workerThread.start();

        try {
            assertTrue(
                       workerStarted.await(5, TimeUnit.SECONDS), "worker thread did not bind context within 5s");
            assertSame(
                       outerCtx,
                       observedOnWorker.get(),
                       "context observed on worker thread MUST be the exact same instance bound on the test "
                               + "thread — identity, not equality");
        } finally {
            testFinished.countDown();
            workerThread.join(2_000L);
        }
    }

    @Test
    void cancellingFromThreadA_isObservedByThreadBWithinPollPeriod() throws Exception {
        CancellationToken                          token          = CancellationToken.create();
        ExecutionContext                           ctx            = freshCtx(token);
        CountDownLatch                             handlerEntered = new CountDownLatch(1);
        AtomicReference<FlowCancellationException> observed       = new AtomicReference<>();

        CompletableFuture<Void> workerDone   = new CompletableFuture<>();
        Thread                  workerThread =
                new Thread(
                        () -> {
                            FlowScope.runWithContext(
                                                     ctx,
                                                     () -> {
                                                         ExecutionContext c = FlowScope.requireCurrent();
                                                         handlerEntered.countDown();
                                                         long deadlineNanos = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                                                         try {
                                                             while (System.nanoTime() < deadlineNanos) {
                                                                 c.throwIfCancelledOrExpired();
                                                                 try {
                                                                     //noinspection BusyWait
                                                                     Thread.sleep(20);
                                                                 } catch (InterruptedException ie) {
                                                                     Thread.currentThread().interrupt();
                                                                     workerDone.completeExceptionally(ie);
                                                                     return;
                                                                 }
                                                             }
                                                             // Safety exit — would mean the cancellation never propagated.
                                                             workerDone.complete(null);
                                                         } catch (FlowCancellationException fce) {
                                                             observed.set(fce);
                                                             workerDone.complete(null);
                                                         }
                                                     });
                        },
                        "cross-thread-cancellation-test-worker");
        workerThread.start();

        assertTrue(
                   handlerEntered.await(5, TimeUnit.SECONDS), "handler did not enter poll loop within 5s");

        long t0 = System.nanoTime();
        token.cancel();
        workerDone.get(2, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertNotNull(
                      observed.get(),
                      "thread B did not observe cancellation through FlowScope.current() — cross-thread "
                              + "propagation broken");
        assertTrue(
                   elapsedMs < 1_000L,
                   "cancellation latency "
                           + elapsedMs
                           + "ms exceeds the cooperative bound (poll period × few × CI noise = 1s)");

        workerThread.join(2_000L);
    }
}
