package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.junit.jupiter.api.Test;

/**
 * fan-out across listeners of a single event is <strong>sequential</strong> and respects
 * <strong>registration order</strong>.
 *
 * <p>This pins two contracts theOutbox relies on:
 *
 * <ul>
 * <li>Listeners run in the exact order they were registered, so a persisting "Outbox listener"
 * registered last sees every other listener's side-effects before it has to ship the event.
 * <li>Listener L_(k+1) starts only after L_k has returned — no overlapping invocations. A
 * regression to a parallel fan-out would let two listeners observe each other "in flight",
 * breaking exactly-once-effective semantics.
 * </ul>
 */
class EventFanOutListenersInRegistrationOrderTest {

    static final class Ping extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Ping(String aggregateId) {
            super(aggregateId);
        }
    }

    /** Multiple listeners execute in registration order with no concurrency. */
    @Test
    void threeListeners_runInRegistrationOrder_andNeverOverlap() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            List<String> log = new CopyOnWriteArrayList<>();
            // Track in-flight invocations so the test fails loudly if a
            // future regression parallelises listeners.
            AtomicBoolean l1Running       = new AtomicBoolean(false);
            AtomicBoolean l2Running       = new AtomicBoolean(false);
            AtomicBoolean overlapDetected = new AtomicBoolean(false);

            var l1 =
                    new AbstractDomainEventListener<Ping>() {
                               @Override
                               public void handle(Ping event) {
                                   if (l1Running.getAndSet(true) || l2Running.get()) {
                                       overlapDetected.set(true);
                                   }
                                   try {
                                       log.add("L1");
                                       // Take observable wall-clock time so a parallel
                                       // regression would let L2 enter while we sleep.
                                       Thread.sleep(25);
                                   } catch (InterruptedException ie) {
                                       Thread.currentThread().interrupt();
                                   } finally {
                                       l1Running.set(false);
                                   }
                               }
                           };
            var l2 =
                    new AbstractDomainEventListener<Ping>() {
                               @Override
                               public void handle(Ping event) {
                                   if (l2Running.getAndSet(true) || l1Running.get()) {
                                       overlapDetected.set(true);
                                   }
                                   try {
                                       log.add("L2");
                                       Thread.sleep(25);
                                   } catch (InterruptedException ie) {
                                       Thread.currentThread().interrupt();
                                   } finally {
                                       l2Running.set(false);
                                   }
                               }
                           };
            var l3 =
                    new AbstractDomainEventListener<Ping>() {
                               @Override
                               public void handle(Ping event) {
                                   // The "Outbox-like" listener: registered last, must
                                   // see L1 and L2 already in the log when it runs.
                                   log.add("L3@seen=" + List.copyOf(log));
                               }
                           };

            // Registration order MUST match invocation order.
            runtime.events().register(l1);
            runtime.events().register(l2);
            runtime.events().register(l3);

            DispatchResult<Void> r =
                    runtime
                            .events()
                            .dispatchResult(new Ping("agg-1"), ExecutionContext.root(), ErrorPolicy.failFast());
            assertInstanceOf(DispatchResult.Success.class, r);

            // Strict ordering: L1 -> L2 -> L3.
            assertEquals(3, log.size(), "every listener must have run exactly once");
            assertEquals("L1", log.get(0), "first registered listener runs first");
            assertEquals("L2", log.get(1), "second registered listener runs second");
            assertEquals(
                         "L3@seen=[L1, L2]",
                         log.get(2),
                         "third (Outbox-like) listener must observe L1+L2 already in the log");
            assertFalse(overlapDetected.get(), " listeners must NEVER run concurrently");
        }
    }
}
