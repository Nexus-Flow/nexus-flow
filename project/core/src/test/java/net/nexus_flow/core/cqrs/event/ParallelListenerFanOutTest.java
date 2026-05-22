package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.junit.jupiter.api.Test;

/**
 * opt-in parallel listener fan-out semantics.
 *
 * <p>Scenarios:
 *
 * <ul>
 * <li>Default runtime (no opt-in): listeners run sequentially even when all of them declare
 * {@code parallelSafe()=true}.
 * <li>parallelListeners(true) + ALL listeners parallel-safe + >1 listener → concurrent execution
 * observable via a latch.
 * <li>parallelListeners(true) + at least one non-parallel-safe listener → falls back to the
 * sequential path.
 * <li>parallelListeners(true) + single listener → sequential path (size==1 short-circuit).
 * </ul>
 */
class ParallelListenerFanOutTest {

    static final class Bumped extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Bumped(String aggId) {
            super(aggId);
        }
    }

    /** Listener that records the thread it ran on AND blocks on a latch. */
    private static AbstractDomainEventListener<Bumped> parallelSafeBlocking(
            CountDownLatch entered,
            CountDownLatch release,
            ConcurrentHashMap<Long, Boolean> threads,
            AtomicInteger handled) {
        return new AbstractDomainEventListener<>() {
            @Override
            public boolean parallelSafe() {
                return true;
            }

            @Override
            public void handle(Bumped event) {
                threads.put(Thread.currentThread().threadId(), Boolean.TRUE);
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("listener latch timed out");
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
                handled.incrementAndGet();
            }
        };
    }

    @Test
    void parallelDisabled_listenersRunSequentially_even_whenAllOptIn() throws Exception {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            EventBus                         bus     = runtime.events();
            CountDownLatch                   entered = new CountDownLatch(2);
            CountDownLatch                   release = new CountDownLatch(1);
            ConcurrentHashMap<Long, Boolean> threads = new ConcurrentHashMap<>();
            AtomicInteger                    handled = new AtomicInteger();
            var                              l1      = parallelSafeBlocking(entered, release, threads, handled);
            var                              l2      = parallelSafeBlocking(entered, release, threads, handled);
            bus.register(l1);
            bus.register(l2);
            try {
                // Run dispatch on a background thread so we can observe
                // sequential semantics from the test thread.
                Thread t =
                        Thread.ofPlatform().daemon(true).start(() -> bus.dispatchResult(new Bumped("a-1")));
                Thread.sleep(200);
                // Sequential path: only ONE listener has entered by now;
                // because L1 holds the release latch the second one can
                // never enter.
                assertEquals(
                             1,
                             entered.getCount(),
                             "sequential dispatch: only 1 listener should have entered (count==1 means 1 still"
                                     + " pending)");
                // Let the first listener finish; the second one runs next.
                release.countDown();
                t.join(2_000);
                assertEquals(2, handled.get());
            } finally {
                bus.unregister(l1);
                bus.unregister(l2);
            }
        }
    }

    @Test
    void parallelEnabled_allParallelSafe_runsListenersConcurrently() throws Exception {
        try (FlowRuntime runtime = FlowRuntime.builder().parallelListeners(true).build()) {
            EventBus                         bus     = runtime.events();
            CountDownLatch                   entered = new CountDownLatch(3);
            CountDownLatch                   release = new CountDownLatch(1);
            ConcurrentHashMap<Long, Boolean> threads = new ConcurrentHashMap<>();
            AtomicInteger                    handled = new AtomicInteger();
            var                              l1      = parallelSafeBlocking(entered, release, threads, handled);
            var                              l2      = parallelSafeBlocking(entered, release, threads, handled);
            var                              l3      = parallelSafeBlocking(entered, release, threads, handled);
            bus.register(l1);
            bus.register(l2);
            bus.register(l3);
            try {
                Thread t =
                        Thread.ofPlatform().daemon(true).start(() -> bus.dispatchResult(new Bumped("a-1")));
                assertTrue(
                           entered.await(5, TimeUnit.SECONDS),
                           "all 3 listeners must have entered concurrently before release");
                // All three listeners ran on distinct worker threads.
                assertTrue(
                           threads.size() >= 2,
                           "parallel fan-out must spawn at least 2 distinct threads, got " + threads.size());
                release.countDown();
                t.join(5_000);
                assertEquals(3, handled.get());
            } finally {
                bus.unregister(l1);
                bus.unregister(l2);
                bus.unregister(l3);
            }
        }
    }

    @Test
    void parallelEnabled_oneNonParallelSafe_fallsBackToSequential() throws Exception {
        try (FlowRuntime runtime = FlowRuntime.builder().parallelListeners(true).build()) {
            EventBus                         bus     = runtime.events();
            CountDownLatch                   entered = new CountDownLatch(2);
            CountDownLatch                   release = new CountDownLatch(1);
            ConcurrentHashMap<Long, Boolean> threads = new ConcurrentHashMap<>();
            AtomicInteger                    handled = new AtomicInteger();
            var                              l1      = parallelSafeBlocking(entered, release, threads, handled);
            // l2 is NOT parallelSafe — default false.
            var l2 =
                    new AbstractDomainEventListener<Bumped>() {
                        @Override
                        public void handle(Bumped event) {
                            threads.put(Thread.currentThread().threadId(), Boolean.TRUE);
                            entered.countDown();
                            try {
                                if (!release.await(2, TimeUnit.SECONDS)) {
                                    throw new AssertionError("listener latch timed out");
                                }
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(ie);
                            }
                            handled.incrementAndGet();
                        }
                    };
            bus.register(l1);
            bus.register(l2);
            try {
                Thread t =
                        Thread.ofPlatform().daemon(true).start(() -> bus.dispatchResult(new Bumped("a-1")));
                Thread.sleep(200);
                assertEquals(
                             1,
                             entered.getCount(),
                             "presence of a non-parallel-safe listener must keep the bus sequential");
                release.countDown();
                t.join(2_000);
                assertEquals(2, handled.get());
            } finally {
                bus.unregister(l1);
                bus.unregister(l2);
            }
        }
    }

    @Test
    void parallelEnabled_singleListener_returnsSuccess() {
        try (FlowRuntime runtime = FlowRuntime.builder().parallelListeners(true).build()) {
            EventBus      bus      = runtime.events();
            AtomicInteger handled  = new AtomicInteger();
            var           listener =
                    new AbstractDomainEventListener<Bumped>() {
                                               @Override
                                               public boolean parallelSafe() {
                                                   return true;
                                               }

                                               @Override
                                               public void handle(Bumped event) {
                                                   handled.incrementAndGet();
                                               }
                                           };
            bus.register(listener);
            try {
                DispatchResult<Void> r =
                        bus.dispatchResult(new Bumped("a-1"), ExecutionContext.root(), ErrorPolicy.failFast());
                assertInstanceOf(DispatchResult.Success.class, r);
                assertEquals(1, handled.get());
            } finally {
                bus.unregister(listener);
            }
        }
    }
}
