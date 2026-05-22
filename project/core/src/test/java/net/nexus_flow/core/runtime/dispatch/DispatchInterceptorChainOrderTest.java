package net.nexus_flow.core.runtime.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.junit.jupiter.api.Test;

/**
 * {@link DispatchInterceptor} onion ordering: first registered is outermost shell.
 *
 * <p>Stacks three named interceptors {@code A}, {@code B}, {@code C} and verifies that a single
 * command dispatch threads them in <em>registration</em> order around the handler:
 *
 * <pre>
 * A_pre → B_pre → C_pre → handler → C_post → B_post → A_post
 * </pre>
 *
 * The first interceptor registered (A) is the outermost shell of the onion, as documented in {@link
 * FlowRuntime.Builder#interceptor}.
 */
class DispatchInterceptorChainOrderTest {

    record Greet(String name) {
    }

    /** Recording interceptor that stamps {@code <name>_pre} / {@code <name>_post}. */
    private static final class Tag implements DispatchInterceptor {
        private final String       name;
        private final List<String> trace;

        Tag(String name, List<String> trace) {
            this.name  = name;
            this.trace = trace;
        }

        @Override
        public <R> DispatchResult<R> intercept(InvocationContext ctx, DispatchChain<R> chain) {
            trace.add(name + "_pre");
            // The mutable invocation attribute bag is shared across every
            // link of the chain; pinning that here guards against a future
            // regression where InvocationContext.withStage(...) might fork
            // a new bag.
            ctx.attributes().put(name + "_pre_seen", true);
            DispatchResult<R> r = chain.proceed();
            trace.add(name + "_post");
            ctx.attributes().put(name + "_post_seen", true);
            return r;
        }
    }

    @Test
    void firstRegisteredInterceptor_isOutermostShell_ofTheOnion() {
        List<String> trace = new CopyOnWriteArrayList<>();
        // CopyOnWriteArrayList: the chain runs single-threaded on the
        // dispatching thread, but using a thread-safe list keeps the
        // test honest if.x ever shifts a link off-thread.

        try (FlowRuntime runtime =
                FlowRuntime.builder()
                        .interceptor(new Tag("A", trace))
                        .interceptor(new Tag("B", trace))
                        .interceptor(new Tag("C", trace))
                        .build()) {

            var handler =
                    new AbstractReturnCommandHandler<Greet, String>() {
                        @Override
                        protected String handle(Greet command) {
                            trace.add("handler");
                            return "ok:" + command.name();
                        }
                    };
            runtime.commands().register(handler);
            try {
                Command<Greet>         cmd = Command.<Greet>builder().body(new Greet("o")).build();
                DispatchResult<String> r   =
                        runtime
                                .commands()
                                .dispatchAndReturnResult(cmd, ExecutionContext.root(), ErrorPolicy.failFast());

                if (!(r instanceof DispatchResult.Success<String> s)) {
                    fail("expected Success, got " + r);
                    return;
                }
                assertEquals("ok:o", s.value());

                assertEquals(
                             List.of("A_pre", "B_pre", "C_pre", "handler", "C_post", "B_post", "A_post"),
                             new ArrayList<>(trace),
                             "First-registered interceptor must be the outermost shell of the onion. "
                                     + "Observed trace deviates from onion order.");
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }

    @Test
    void interceptors_accessor_returnsRegistrationOrder_andIsImmutable() {
        var a = new Tag("A", new ArrayList<>());
        var b = new Tag("B", new ArrayList<>());
        try (FlowRuntime runtime = FlowRuntime.builder().interceptor(a).interceptor(b).build()) {
            List<DispatchInterceptor> ints = runtime.interceptors();
            assertEquals(2, ints.size());
            assertEquals(a, ints.get(0));
            assertEquals(b, ints.get(1));
            try {
                ints.add(new Tag("X", new ArrayList<>()));
                fail("interceptors() must be immutable");
            } catch (UnsupportedOperationException expected) {
                assertInstanceOf(UnsupportedOperationException.class, expected);
            }
        }
    }

    @Test
    void zeroInterceptors_handlerStillReceivesTheCommand() {
        // Smoke test guarding the fast path: with no interceptors
        // registered, the SyncDispatcher.dispatchThrough fast path runs
        // the terminal Callable directly. Functionally identical to the
        // pre-2.4 wiring; pinned here so a future refactor of that fast
        // path is forced to update this test on purpose.
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            assertTrue(runtime.interceptors().isEmpty());
            var handler =
                    new AbstractReturnCommandHandler<Greet, String>() {
                        @Override
                        protected String handle(Greet command) {
                            return "hi-" + command.name();
                        }
                    };
            runtime.commands().register(handler);
            try {
                DispatchResult<String> r =
                        runtime
                                .commands()
                                .dispatchAndReturnResult(
                                                         Command.<Greet>builder().body(new Greet("z")).build(),
                                                         ExecutionContext.root(),
                                                         ErrorPolicy.failFast());
                assertEquals("hi-z", ((DispatchResult.Success<String>) r).value());
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }
}
