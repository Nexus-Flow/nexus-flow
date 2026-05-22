package net.nexus_flow.core.cqrs.command;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.runtime.CancellationToken;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.FlowScope;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link CommandBus#dispatch(Command, ExecutionContext)} default-method overload.
 *
 * <p>The overload exists so worker code (outbox, saga, scheduler) that owns an {@link
 * ExecutionContext} can dispatch without manually wrapping the call in {@link
 * FlowScope#runWithContext} — a pattern that is correct but easy to forget and adds noise to every
 * call site.
 *
 * <p>Contract verified here:
 *
 * <ul>
 * <li>Calling {@code commandBus.dispatch(cmd, ctx)} binds {@code ctx} as the current {@link
 * FlowScope#CURRENT_CONTEXT} for the duration of the dispatch. The handler observes the exact
 * {@link ExecutionContext} instance the caller passed in (identity equality).
 * <li>Passing a {@code null} context raises {@link NullPointerException} immediately, before any
 * handler work happens.
 * <li>The handler's cancellation hooks observe the token embedded in the supplied context — i.e.
 * the binding is structural, not just a name lookup.
 * </ul>
 */
class CommandBusDispatchWithContextBindsScopeTest {

    record Ping(int n) {
    }

    /** Handler that captures the {@link FlowScope#current()} context observed at dispatch time. */
    static final class CapturingHandler extends AbstractNoReturnCommandHandler<Ping> {
        final AtomicReference<ExecutionContext> seenContext = new AtomicReference<>();
        final CountDownLatch                    done        = new CountDownLatch(1);

        @Override
        protected void handle(Ping cmd) {
            // FlowScope.current() must report the EXACT context the caller bound — not the root
            // context, not a freshly-rebuilt one. The overload contract is identity-preserving.
            ExecutionContext ctx = FlowScope.current().orElseThrow();
            seenContext.set(ctx);
            done.countDown();
        }
    }

    @Test
    void dispatchWithContext_bindsExactContextAsObservableByHandler() throws Exception {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            CapturingHandler handler = new CapturingHandler();
            runtime.commands().register(handler);

            ExecutionContext myCtx =
                    new ExecutionContext(
                            MessageId.random(),
                            TraceId.random(),
                            CorrelationId.random(),
                            CausationId.ROOT,
                            null,
                            null,
                            null,
                            CancellationToken.create(),
                            java.util.Map.of("test.marker", "dispatch-with-ctx"));

            runtime.commands().dispatch(Command.<Ping>builder().body(new Ping(7)).build(), myCtx);

            assertTrue(handler.done.await(5, TimeUnit.SECONDS), "handler must run within 5s");
            ExecutionContext observed = handler.seenContext.get();
            assertNotNull(observed, "handler must observe a bound ExecutionContext");
            assertSame(
                       myCtx,
                       observed,
                       "handler MUST observe the exact ExecutionContext passed to dispatch(cmd, ctx) — "
                               + "binding identity, not a rebuild or root fallback");
        }
    }

    @Test
    void dispatchWithContext_nullContext_throwsImmediately() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            CapturingHandler handler = new CapturingHandler();
            runtime.commands().register(handler);

            assertThrows(
                         NullPointerException.class,
                         () -> runtime.commands().dispatch(Command.<Ping>builder().body(new Ping(1)).build(), null));
        }
    }

    @Test
    void dispatchWithContext_cancellationTokenInContext_observableInHandler() throws Exception {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            CapturingHandler handler = new CapturingHandler();
            runtime.commands().register(handler);

            CancellationToken token = CancellationToken.create();
            ExecutionContext  myCtx =
                    new ExecutionContext(
                            MessageId.random(),
                            TraceId.random(),
                            CorrelationId.random(),
                            CausationId.ROOT,
                            null,
                            null,
                            null,
                            token,
                            java.util.Map.of());

            runtime.commands().dispatch(Command.<Ping>builder().body(new Ping(2)).build(), myCtx);
            assertTrue(handler.done.await(5, TimeUnit.SECONDS));

            // Handler observed the same token instance via its context — proving structural binding.
            assertSame(
                       token,
                       handler.seenContext.get().cancellation(),
                       "handler's observed context must expose the same CancellationToken instance");
        }
    }

    @Test
    void dispatchWithContext_completesWithinReasonableTime() throws Exception {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            CapturingHandler handler = new CapturingHandler();
            runtime.commands().register(handler);

            ExecutionContext myCtx = ExecutionContext.root();
            long             t0    = System.nanoTime();
            runtime.commands().dispatch(Command.<Ping>builder().body(new Ping(3)).build(), myCtx);
            assertTrue(handler.done.await(2, TimeUnit.SECONDS));
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            assertTrue(
                       Duration.ofMillis(elapsedMs).compareTo(Duration.ofSeconds(2)) < 0,
                       "dispatch(cmd, ctx) overhead too large: " + elapsedMs + "ms");
        }
    }
}
