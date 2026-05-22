package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.query.AbstractQueryHandler;
import net.nexus_flow.core.cqrs.query.Query;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import org.junit.jupiter.api.Test;

/**
 * Wiring contract for {@link FlowRuntime}: each runtime owns a fresh trio of buses.hardened the
 * contract — two runtimes are fully isolated; see {@code TwoRuntimesIsolatedHandlerRegistriesTest}
 * for the per-handler isolation guarantee.
 */
class FlowRuntimeWiringTest {

    record Greet(String name) {
    }

    record AskGreeting(String name) {
    }

    static final class GreetingEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;
        final String              name;

        GreetingEvent(String name) {
            super("greet:" + name);
            this.name = name;
        }
    }

    @Test
    void twoRuntimes_returnDistinctBusInstances() {
        // per-runtime isolation invariant. Two builders
        // produce two independent bus graphs; no singleton aliasing.
        try (FlowRuntime a = FlowRuntime.builder().build();
                FlowRuntime b = FlowRuntime.builder().build()) {
            assertNotSame(a.commands(), b.commands());
            assertNotSame(a.queries(), b.queries());
            assertNotSame(a.events(), b.events());
            assertNotSame(a.executor(), b.executor());
        }
    }

    @Test
    void runtime_dispatchesCommands() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var handler =
                    new AbstractReturnCommandHandler<Greet, String>() {
                        @Override
                        protected String handle(Greet command) {
                            return "hello:" + command.name();
                        }
                    };
            runtime.commands().register(handler);
            try {
                Command<Greet> cmd    = Command.<Greet>builder().body(new Greet("nexus")).build();
                String         result = runtime.commands().dispatchAndReturn(cmd);
                assertEquals("hello:nexus", result);
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }

    @Test
    void runtime_dispatchesQueries() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var handler =
                    new AbstractQueryHandler<AskGreeting, String>() {
                        @Override
                        public String handle(AskGreeting query) {
                            return "hi:" + query.name();
                        }
                    };
            runtime.queries().register(handler);
            try {
                Query<AskGreeting> q      = Query.<AskGreeting>builder().body(new AskGreeting("nexus")).build();
                String             result = runtime.queries().ask(q);
                assertEquals("hi:nexus", result);
            } finally {
                runtime.queries().unregister(handler);
            }
        }
    }

    @Test
    void runtime_dispatchesEvents() throws InterruptedException {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            CountDownLatch          latch    = new CountDownLatch(1);
            AtomicReference<String> seen     = new AtomicReference<>();
            var                     listener =
                    new AbstractDomainEventListener<GreetingEvent>() {
                                                         @Override
                                                         public void handle(GreetingEvent event) {
                                                             seen.set(event.name);
                                                             latch.countDown();
                                                         }
                                                     };
            runtime.events().register(listener);
            try {
                runtime.events().dispatch(new GreetingEvent("nexus"), false);
                assertTrue(latch.await(2, TimeUnit.SECONDS), "event listener must be invoked");
                assertEquals("nexus", seen.get());
            } finally {
                runtime.events().unregister(listener);
            }
        }
    }

    @Test
    void closedRuntime_rejectsBusAccess() {
        FlowRuntime runtime = FlowRuntime.builder().build();
        runtime.close();
        assertThrows(IllegalStateException.class, runtime::commands);
        assertThrows(IllegalStateException.class, runtime::queries);
        assertThrows(IllegalStateException.class, runtime::events);
    }
}
