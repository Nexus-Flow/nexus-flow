package net.nexus_flow.core.cqrs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.command.AbstractNoReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.command.CommandBus;
import net.nexus_flow.core.cqrs.command.exceptions.CommandHandlerExecutionError;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.cqrs.query.AbstractQueryHandler;
import net.nexus_flow.core.cqrs.query.Query;
import net.nexus_flow.core.cqrs.query.QueryBus;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the core CQRS buses: {@link CommandBus}, {@link EventBus}, and {@link
 * QueryBus}. Each test owns a fresh {@link FlowRuntime} via try-with-resources to ensure handler
 * registrations are isolated and never leak between tests. Tests verify command dispatch (with and
 * without return values), event broadcast and listener lifecycle, query synchronous response, and
 * correctness of handler execution and cleanup.
 */
class CoreIntegrationTest {
    record Ping(String id) {
    }

    record Echo(String value) {
    }

    record AskName(String id) {
    }

    @Test
    void dispatchCommandWithReturnValue_returnsHandlerResult() {
        // A return command handler must return the result it computes.
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            CommandBus bus     = runtime.commands();
            var        handler =
                    new AbstractReturnCommandHandler<Echo, String>() {
                                           @Override
                                           protected String handle(Echo command) {
                                               return "echoed:" + command.value();
                                           }
                                       };
            bus.register(handler);
            try {
                Command<Echo> cmd      = Command.<Echo>builder().body(new Echo("hello")).build();
                String        response = bus.dispatchAndReturn(cmd);
                assertEquals("echoed:hello", response);
            } finally {
                bus.unregister(handler);
            }
        }
    }

    @Test
    void dispatchCommandWithReturnValueThatThrows_wrapsInCommandHandlerExecutionError() {
        // Exceptions in return command handlers must be wrapped for uniform error handling.
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            CommandBus bus     = runtime.commands();
            var        boom    = new IllegalStateException("boom");
            var        handler =
                    new AbstractReturnCommandHandler<Echo, String>() {
                                           @Override
                                           protected String handle(Echo command) {
                                               throw boom;
                                           }
                                       };
            bus.register(handler);
            try {
                Command<Echo>                cmd = Command.<Echo>builder().body(new Echo("x")).build();
                CommandHandlerExecutionError err =
                        assertThrows(CommandHandlerExecutionError.class, () -> bus.dispatchAndReturn(cmd));
                assertNotNull(err.getCause(), "cause must be preserved");
                assertSame(boom, err.getCause(), "cause must be the original exception");
            } finally {
                bus.unregister(handler);
            }
        }
    }

    @Test
    void sagaEnabledHandlerThatThrows_propagatesExceptionToCaller() {
        // Saga-enabled handlers run inline, so their exceptions propagate directly to the caller.
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            CommandBus bus     = runtime.commands();
            var        boom    = new IllegalArgumentException("nope");
            var        handler =
                    new AbstractNoReturnCommandHandler<Ping>() {
                                           @Override
                                           public boolean isSagaEnabled() {
                                               return true; // saga -> runs the task INLINE
                                           }

                                           @Override
                                           protected void handle(Ping command) {
                                               throw boom;
                                           }
                                       };
            bus.register(handler);
            try {
                Command<Ping>                cmd = Command.<Ping>builder().body(new Ping("p")).build();
                CommandHandlerExecutionError err =
                        assertThrows(CommandHandlerExecutionError.class, () -> bus.dispatch(cmd));
                assertSame(boom, err.getCause());
            } finally {
                bus.unregister(handler);
            }
        }
    }

    static final class Counter extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void bump(String id, int v) {
            recordEvent(new Bumped(id, v));
        }
    }

    static final class Bumped extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;
        final int                 value;

        Bumped(String aggregateId, int value) {
            super(aggregateId);
            this.value = value;
        }
    }

    @Test
    void aggregateRecordsEvents() {
        // Domain aggregates must record events via the AggregateRoot contract.
        Counter c = new Counter();
        c.bump("A", 1);
        c.bump("A", 2);
        assertTrue(c.getEvents().size() >= 2, "Aggregate#getEvents must report the events it recorded");
    }

    @Test
    void eventBusDispatchesToRegisteredListener() throws InterruptedException {
        // Event listeners registered with the bus must receive dispatched events.
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            EventBus                eventBus = runtime.events();
            CountDownLatch          latch    = new CountDownLatch(1);
            AtomicReference<Bumped> seen     = new AtomicReference<>();
            var                     listener =
                    new AbstractDomainEventListener<Bumped>() {
                                                         @Override
                                                         public void handle(Bumped event) {
                                                             seen.set(event);
                                                             latch.countDown();
                                                         }
                                                     };
            eventBus.register(listener);
            try {
                eventBus.dispatch(new Bumped("agg-1", 42), true);
                assertTrue(latch.await(2, TimeUnit.SECONDS), "listener must run");
                assertEquals(42, seen.get().value);
            } finally {
                eventBus.unregister(listener);
            }
        }
    }

    @Test
    void queryBusReturnsHandlerResultSynchronously() {
        // Query handlers must return their result synchronously through the query bus.
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            QueryBus qb      = runtime.queries();
            var      handler =
                    new AbstractQueryHandler<AskName, String>() {
                                         @Override
                                         public String handle(AskName query) {
                                             return "name:" + query.id();
                                         }
                                     };
            qb.register(handler);
            try {
                Query<AskName> q = Query.<AskName>builder().body(new AskName("42")).build();
                assertEquals("name:42", qb.ask(q));
            } finally {
                qb.unregister(handler);
            }
        }
    }

    @Test
    void unregisteringHandlerDoesNotKillSiblingHandlers() {
        // Unregistering a handler must not corrupt the shared virtual-thread executor for other
        // handlers.
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            CommandBus    bus   = runtime.commands();
            AtomicInteger seenA = new AtomicInteger();
            AtomicInteger seenB = new AtomicInteger();
            var           a     =
                    new AbstractReturnCommandHandler<Echo, Integer>() {
                                            @Override
                                            protected Integer handle(Echo command) {
                                                return seenA.incrementAndGet();
                                            }
                                        };
            record OtherEcho(String v) {
            }
            var b =
                    new AbstractReturnCommandHandler<OtherEcho, Integer>() {
                        @Override
                        protected Integer handle(OtherEcho command) {
                            return seenB.incrementAndGet();
                        }
                    };
            bus.register(a);
            bus.register(b);
            try {
                assertEquals(
                             Integer.valueOf(1),
                             bus.dispatchAndReturn(Command.<Echo>builder().body(new Echo("1")).build()));
                bus.unregister(a);
                Integer second =
                        bus.dispatchAndReturn(Command.<OtherEcho>builder().body(new OtherEcho("2")).build());
                assertEquals(
                             Integer.valueOf(1),
                             second,
                             "handler B must keep working after handler A is unregistered");
            } finally {
                bus.unregister(b);
            }
        }
    }
}
