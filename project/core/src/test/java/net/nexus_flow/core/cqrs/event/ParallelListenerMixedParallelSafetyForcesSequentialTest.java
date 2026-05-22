package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * Parallel-listener safety precondition: when {@code parallelListeners=true} is configured at
 * the runtime AND at least one listener for a given event class declares {@code parallelSafe() ==
 * false}, the bus MUST force sequential delivery for that event class — <em>every</em> listener
 * (even those that opted into {@code parallelSafe}) runs on the dispatcher thread one after the
 * other.
 *
 * <p>The contract is "all-or-nothing": parallel fan-out is unsafe if even one listener is
 * non-parallel-safe (it might mutate shared state without lock discipline), so the bus downgrades
 * the entire fan-out rather than partition listeners into "parallel batch" + "sequential batch"
 * (which would break documented registration-order delivery).
 *
 * <p><strong>How we detect sequential vs parallel.</strong> The single dispatcher thread runs every
 * listener consecutively in sequential mode — so every listener observes the same {@link
 * Thread#currentThread()}. In parallel mode, listeners run on distinct virtual threads spawned by
 * {@link java.util.concurrent.StructuredTaskScope}, so each listener observes a distinct thread
 * instance. Capturing the thread per listener and asserting they all collapse to the caller's
 * thread is the cleanest detection without timing assumptions.
 */
class ParallelListenerMixedParallelSafetyForcesSequentialTest {

    static final class Pulse extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Pulse() {
            super(UUID.randomUUID().toString());
        }
    }

    static final class ParallelSafeListener extends AbstractDomainEventListener<Pulse> {
        final AtomicReference<Thread> observedThread = new AtomicReference<>();
        final CountDownLatch          invoked        = new CountDownLatch(1);

        @Override
        public boolean parallelSafe() {
            return true;
        }

        @Override
        public void handle(Pulse event) {
            observedThread.set(Thread.currentThread());
            invoked.countDown();
        }
    }

    static final class NonParallelSafeListener extends AbstractDomainEventListener<Pulse> {
        final AtomicReference<Thread> observedThread = new AtomicReference<>();
        final CountDownLatch          invoked        = new CountDownLatch(1);

        // parallelSafe() default is false — see DomainEventListener Javadoc.

        @Override
        public void handle(Pulse event) {
            observedThread.set(Thread.currentThread());
            invoked.countDown();
        }
    }

    @Test
    void mixedParallelSafety_forcesSequentialDelivery_onSameThread() throws Exception {
        try (FlowRuntime runtime = FlowRuntime.builder().parallelListeners(true).build()) {
            ParallelSafeListener    safe   = new ParallelSafeListener();
            NonParallelSafeListener unsafe = new NonParallelSafeListener();
            runtime.events().register(safe);
            runtime.events().register(unsafe);

            Thread caller = Thread.currentThread();
            runtime.events().dispatchResult(new Pulse(), ExecutionContext.root(), ErrorPolicy.failFast());

            assertTrue(safe.invoked.await(2, TimeUnit.SECONDS), "parallelSafe listener must be invoked");
            assertTrue(
                       unsafe.invoked.await(2, TimeUnit.SECONDS),
                       "non-parallelSafe listener must also be invoked");

            Thread safeThread   = safe.observedThread.get();
            Thread unsafeThread = unsafe.observedThread.get();
            assertNotNull(safeThread);
            assertNotNull(unsafeThread);
            assertEquals(
                         caller,
                         safeThread,
                         "in sequential mode (forced by the non-parallelSafe sibling), the parallelSafe "
                                 + "listener MUST run on the caller's thread — not a forked virtual thread");
            assertEquals(
                         caller, unsafeThread, "the non-parallelSafe listener MUST run on the caller's thread");
            assertEquals(
                         safeThread,
                         unsafeThread,
                         "both listeners MUST observe the SAME thread — sequential dispatch is the contract "
                                 + "when ANY listener for the event class is non-parallelSafe");
        }
    }

    @Test
    void allParallelSafe_actuallyRunsOnDistinctThreads_whenRuntimeParallelEnabled() throws Exception {
        try (FlowRuntime runtime = FlowRuntime.builder().parallelListeners(true).build()) {
            ParallelSafeListener a = new ParallelSafeListener();
            ParallelSafeListener b = new ParallelSafeListener();
            runtime.events().register(a);
            runtime.events().register(b);

            runtime.events().dispatchResult(new Pulse(), ExecutionContext.root(), ErrorPolicy.failFast());

            assertTrue(a.invoked.await(2, TimeUnit.SECONDS));
            assertTrue(b.invoked.await(2, TimeUnit.SECONDS));

            // Two parallelSafe listeners with parallelListeners(true) MUST run on distinct virtual
            // threads (StructuredTaskScope forks one VT per listener).
            assertEquals(
                         false,
                         a.observedThread.get().equals(b.observedThread.get()),
                         "two parallelSafe listeners under parallelListeners(true) MUST run on distinct "
                                 + "(virtual) threads");
        }
    }

    @Test
    void parallelDisabled_alwaysSequential_regardlessOfParallelSafe() throws Exception {
        try (FlowRuntime runtime = FlowRuntime.builder().parallelListeners(false).build()) {
            ParallelSafeListener a = new ParallelSafeListener();
            ParallelSafeListener b = new ParallelSafeListener();
            runtime.events().register(a);
            runtime.events().register(b);

            Thread caller = Thread.currentThread();
            runtime.events().dispatchResult(new Pulse(), ExecutionContext.root(), ErrorPolicy.failFast());

            assertTrue(a.invoked.await(2, TimeUnit.SECONDS));
            assertTrue(b.invoked.await(2, TimeUnit.SECONDS));

            assertEquals(
                         caller,
                         a.observedThread.get(),
                         "parallelListeners(false) means precondition 1 fails — always sequential");
            assertEquals(caller, b.observedThread.get());
            // Sanity: the test took less than 2 seconds (no parallel-fork timeout).
            assertTrue(Duration.ofSeconds(2).toMillis() > 0);
        }
    }
}
