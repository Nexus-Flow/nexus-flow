package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.Serial;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.junit.jupiter.api.Test;

/**
 * events recorded on an aggregate are dispatched to listeners <strong>in the order they were
 * recorded</strong>.
 *
 * <p>TheOutbox relies on this: a persisting listener registered last must see the events in the
 * exact emission order so the outbox table mirrors the aggregate's intent.
 */
class EventOrderingByAggregateRecordingTest {

    /**
     * Three distinct event types so the test can read the listener log back as a sequence of class
     * simple names.
     */
    static final class Created extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Created(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Updated extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Updated(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Closed extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Closed(String aggregateId) {
            super(aggregateId);
        }
    }

    /** Aggregate that emits exactly the three events above, in order. */
    static final class Cart extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void create() {
            recordEvent(new Created("cart-1"));
        }

        void update() {
            recordEvent(new Updated("cart-1"));
        }

        void closeIt() {
            recordEvent(new Closed("cart-1"));
        }
    }

    /** Command that drives the aggregate through its lifecycle in one shot. */
    record DoCart(String tag) {
    }

    /** Listeners observe events in the order the aggregate recorded them. */
    @Test
    void listenersObserveEvents_inAggregateRecordingOrder() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            // Single shared log — listeners only contribute to it, so
            // the resulting order reflects what the bus surfaced.
            List<String> seen = new CopyOnWriteArrayList<>();

            var lCreated =
                    new AbstractDomainEventListener<Created>() {
                                     @Override
                                     public void handle(Created event) {
                                         seen.add("Created@" + event.getSequenceNumber());
                                     }
                                 };
            var lUpdated =
                    new AbstractDomainEventListener<Updated>() {
                                     @Override
                                     public void handle(Updated event) {
                                         seen.add("Updated@" + event.getSequenceNumber());
                                     }
                                 };
            var lClosed  =
                    new AbstractDomainEventListener<Closed>() {
                                     @Override
                                     public void handle(Closed event) {
                                         seen.add("Closed@" + event.getSequenceNumber());
                                     }
                                 };
            runtime.events().register(lCreated);
            runtime.events().register(lUpdated);
            runtime.events().register(lClosed);

            var handler =
                    new AbstractReturnCommandHandler<DoCart, String>() {
                        @Override
                        protected String handle(DoCart command) {
                            Cart cart = new Cart();
                            cart.create();
                            cart.update();
                            cart.closeIt();
                            // Option A: drain to the JVM-wide sink via the
                            // legacy bridge — DefaultCommandBus.dispatchAndReturnResultBody
                            // picks the events up and calls publishEvents in
                            // the same order the aggregate emitted them.
                            List<DomainEvent> drained = cart.drainEvents();
                            // Re-record into the sink so the legacy fan-out
                            // path consumes them (the bus reads from
                            // DomainEventContext.current() when the handler's
                            // CommandResult is not used).
                            drained.forEach(
                                            e -> net.nexus_flow.core.cqrs.event.DomainEventContext.current().recordEvent(e));
                            return command.tag();
                        }
                    };
            runtime.commands().register(handler);

            DispatchResult<String> r =
                    runtime
                            .commands()
                            .dispatchAndReturnResult(
                                                     Command.<DoCart>builder().body(new DoCart("ok")).build(),
                                                     ExecutionContext.root(),
                                                     ErrorPolicy.failFast());
            assertInstanceOf(DispatchResult.Success.class, r, "command dispatch must succeed; got " + r);

            // three events, three listeners, strict
            // record-on-aggregate order. The sequence number suffix
            // proves the events are the ones the aggregate emitted
            // (and not some retry / replay).
            assertEquals(
                         List.of("Created@0", "Updated@1", "Closed@2"),
                         seen,
                         " listeners must observe events in aggregate "
                                 + "recording order, with monotonic sequence numbers");
        }
    }
}
