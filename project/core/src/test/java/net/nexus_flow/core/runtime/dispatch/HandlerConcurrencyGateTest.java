package net.nexus_flow.core.runtime.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link HandlerConcurrencyGate} enforces concurrency bounds on handler execution.
 *
 * <p>Three load-bearing guarantees:
 *
 * <ul>
 * <li>{@code concurrencyLevel = 1} serialises concurrent dispatches.
 * <li>{@code concurrencyLevel = N} permits up to {@code N} parallel dispatches.
 * <li>The permit is released <strong>after</strong> the handler completes, not before — so the
 * gate actually bounds in-flight work (verified through barriers).
 * </ul>
 */
class HandlerConcurrencyGateTest {

    @Test
    void concurrencyLevelOne_serialisesConcurrentDispatches() throws Exception {
        HandlerConcurrencyGate gate        = new HandlerConcurrencyGate();
        Object                 key         = "handler-A";
        AtomicInteger          inFlight    = new AtomicInteger();
        AtomicInteger          maxObserved = new AtomicInteger();
        int                    N           = 16;
        CountDownLatch         done        = new CountDownLatch(N);

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int i = 0; i < N; i++) {
                pool.submit(
                            () -> gate.runGated(
                                                key,
                                                1,
                                                () -> {
                                                    int now = inFlight.incrementAndGet();
                                                    maxObserved.accumulateAndGet(now, Math::max);
                                                    try {
                                                        Thread.sleep(10);
                                                    } catch (InterruptedException ie) {
                                                        Thread.currentThread().interrupt();
                                                    }
                                                    inFlight.decrementAndGet();
                                                    done.countDown();
                                                    return null;
                                                }));
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "all tasks must complete");
            assertEquals(1, maxObserved.get(), "concurrencyLevel=1 must serialise concurrent dispatches");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrencyLevelN_permitsUpToNInParallel() throws Exception {
        HandlerConcurrencyGate gate        = new HandlerConcurrencyGate();
        Object                 key         = "handler-B";
        int                    permits     = 4;
        int                    N           = 32;
        AtomicInteger          inFlight    = new AtomicInteger();
        AtomicInteger          maxObserved = new AtomicInteger();
        CountDownLatch         done        = new CountDownLatch(N);

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int i = 0; i < N; i++) {
                pool.submit(
                            () -> gate.runGated(
                                                key,
                                                permits,
                                                () -> {
                                                    int now = inFlight.incrementAndGet();
                                                    maxObserved.accumulateAndGet(now, Math::max);
                                                    try {
                                                        Thread.sleep(20);
                                                    } catch (InterruptedException ie) {
                                                        Thread.currentThread().interrupt();
                                                    }
                                                    inFlight.decrementAndGet();
                                                    done.countDown();
                                                    return null;
                                                }));
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "all tasks must complete");
            // We expect at least 2 in parallel (proves we don't fully serialise)
            // and at most `permits` (proves the gate bounds in-flight work).
            assertTrue(
                       maxObserved.get() >= 2,
                       "concurrencyLevel=" + permits + " must allow >1 in parallel; was " + maxObserved.get());
            assertTrue(
                       maxObserved.get() <= permits,
                       "concurrencyLevel="
                               + permits
                               + " must bound in-flight at "
                               + permits
                               + "; was "
                               + maxObserved.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void permitIsReleasedAfterExecution_evenOnException() throws Exception {
        // The permit must be released in `finally` AFTER the body
        // returns or throws. We prove it by having the first runGated()
        // throw, then verifying a second gated call (with permits=1) can
        // still acquire — which would deadlock if the permit had leaked.
        HandlerConcurrencyGate gate = new HandlerConcurrencyGate();
        Object                 key  = "handler-C";

        try {
            gate.runGated(
                          key,
                          1,
                          () -> {
                              throw new RuntimeException("boom");
                          });
            fail("first call must propagate the exception");
        } catch (RuntimeException expected) {
            assertEquals("boom", expected.getMessage());
        }

        // If the permit had not been released, this call would block forever.
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CountDownLatch ran = new CountDownLatch(1);
            pool.submit(
                        () -> gate.runGated(
                                            key,
                                            1,
                                            () -> {
                                                ran.countDown();
                                                return null;
                                            }));
            assertTrue(
                       ran.await(2, TimeUnit.SECONDS),
                       "second gated call must acquire — proves the permit was released after exception");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void permitHeldDuringExecution_blocksAdditionalAcquirers() throws Exception {
        // Strong release-AFTER-execution barrier test: while task A is
        // running under the only permit, task B must be parked. A finishing
        // is the only thing that lets B start.
        HandlerConcurrencyGate gate       = new HandlerConcurrencyGate();
        Object                 key        = "handler-D";
        CountDownLatch         aStarted   = new CountDownLatch(1);
        CountDownLatch         aMayFinish = new CountDownLatch(1);
        CountDownLatch         bStarted   = new CountDownLatch(1);

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            pool.submit(
                        () -> gate.runGated(
                                            key,
                                            1,
                                            () -> {
                                                aStarted.countDown();
                                                try {
                                                    aMayFinish.await();
                                                } catch (InterruptedException ie) {
                                                    Thread.currentThread().interrupt();
                                                }
                                                return null;
                                            }));
            assertTrue(aStarted.await(2, TimeUnit.SECONDS), "A must start");

            pool.submit(
                        () -> gate.runGated(
                                            key,
                                            1,
                                            () -> {
                                                bStarted.countDown();
                                                return null;
                                            }));
            // B must NOT start until A releases.
            assertTrue(
                       gate.awaitWaiters(key, 1, 1_000),
                       "B must be parked at the gate while A holds the permit");
            assertEquals(1, bStarted.getCount(), "B must not run while A holds the only permit");

            // Release A — B should run promptly.
            aMayFinish.countDown();
            assertTrue(bStarted.await(2, TimeUnit.SECONDS), "B must run after A releases the permit");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void permitsZero_isPassThrough() {
        HandlerConcurrencyGate gate = new HandlerConcurrencyGate();
        // permits=0 means no gating — just call the body.
        String r = gate.runGated("any", 0, () -> "ok");
        assertEquals("ok", r);
        assertEquals(null, gate.stats("any"), "permits=0 must not allocate a Semaphore");
    }

    @Test
    void inconsistentPermits_forSameKey_failsFast() {
        HandlerConcurrencyGate gate = new HandlerConcurrencyGate();
        gate.runGated("k", 2, () -> null);
        assertThrows(IllegalArgumentException.class, () -> gate.runGated("k", 5, () -> null));
    }

    @Test
    void rejectsNegativePermits() {
        HandlerConcurrencyGate gate = new HandlerConcurrencyGate();
        assertThrows(IllegalArgumentException.class, () -> gate.runGated("k", -1, () -> null));
    }
}
