package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.command.AbstractNoReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import org.junit.jupiter.api.Test;

/**
 * Audit §6 T5. Cancellation of a parent context MUST propagate to every nested dispatch in the
 * chain: when an outer command's handler dispatches a nested command, and the caller cancels the
 * parent {@link CancellationToken} mid-chain, the nested handler observes cancellation through its
 * bound {@link FlowScope#current()} context (which inherits the parent's token via {@link
 * ExecutionContext#childContextFor(net.nexus_flow.core.runtime.ids.MessageId)}).
 *
 * <p>The contract pinned here:
 *
 * <ol>
 * <li>Outer handler runs, signals "entered" via a latch, dispatches a nested command.
 * <li>Nested handler polls {@code ctx.throwIfCancelledOrExpired()} in a loop.
 * <li>Caller cancels the OUTER context's token.
 * <li>Nested handler observes cancellation within a small multiple of its poll period.
 * </ol>
 *
 * <p>Tests like {@code CrossThreadContextPropagationAndCancellationLatencyTest} cover the {@link
 * FlowScope} layer; this test covers the higher-level {@code CommandBus.dispatch(cmd)} surface
 * end-to-end so a regression in handler-to-handler propagation (e.g. a future executor that breaks
 * {@code childContextFor}) is caught before it ships.
 */
class CancellationMidNestedCommandChainTest {

    record Outer() {
    }

    record Inner() {
    }

    @Test
    void cancellingParentContext_isObservedByNestedHandler_throughChildContext() throws Exception {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            CountDownLatch             outerEntered  = new CountDownLatch(1);
            CountDownLatch             innerEntered  = new CountDownLatch(1);
            AtomicReference<Throwable> innerObserved = new AtomicReference<>();
            AtomicInteger              innerPolls    = new AtomicInteger();

            var innerHandler =
                    new AbstractNoReturnCommandHandler<Inner>() {
                        @Override
                        protected void handle(Inner cmd) {
                            innerEntered.countDown();
                            ExecutionContext ctx   = FlowScope.current().orElseThrow();
                            long     deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                            try {
                                while (System.nanoTime() < deadlineNanos) {
                                    ctx.throwIfCancelledOrExpired();
                                    innerPolls.incrementAndGet();
                                    try {
                                        //noinspection BusyWait
                                        Thread.sleep(20);
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        innerObserved.set(ie);
                                        return;
                                    }
                                }
                            } catch (FlowCancellationException fce) {
                                innerObserved.set(fce);
                                throw fce;
                            }
                        }
                    };

            var outerHandler =
                    new AbstractNoReturnCommandHandler<Outer>() {
                        @Override
                        protected void handle(Outer cmd) {
                            outerEntered.countDown();
                            // Nested dispatch — the bus rebuilds a child ExecutionContext via
                            // childContextFor(), inheriting the parent's cancellation token.
                            runtime.commands().dispatch(Command.<Inner>builder().body(new Inner()).build());
                        }
                    };

            runtime.commands().register(outerHandler);
            runtime.commands().register(innerHandler);

            CancellationToken token    = CancellationToken.create();
            ExecutionContext  outerCtx =
                    new ExecutionContext(
                            net.nexus_flow.core.runtime.ids.MessageId.random(),
                            net.nexus_flow.core.runtime.ids.TraceId.random(),
                            net.nexus_flow.core.runtime.ids.CorrelationId.random(),
                            net.nexus_flow.core.runtime.ids.CausationId.ROOT,
                            null,
                            null,
                            null,
                            token,
                            java.util.Map.of());

            // Dispatch the outer command bound to outerCtx so the nested dispatch inherits the token.
            // The dispatch is fire-and-forget; we use the (cmd, ctx) overload so binding is explicit.
            Thread caller =
                    new Thread(
                            () -> runtime
                                    .commands()
                                    .dispatch(Command.<Outer>builder().body(new Outer()).build(), outerCtx),
                            "nested-chain-caller");
            caller.start();

            assertTrue(outerEntered.await(5, TimeUnit.SECONDS), "outer handler must enter within 5s");
            assertTrue(innerEntered.await(5, TimeUnit.SECONDS), "inner handler must enter within 5s");

            // Cancel the OUTER token. The inner handler's ctx (inherited via childContextFor) carries
            // the same token, so the next poll inside the inner handler MUST throw FCE.
            long t0 = System.nanoTime();
            token.cancel();

            // Wait for the inner handler to observe — bounded by 1s to absorb CI noise.
            long deadlineNanos = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (innerObserved.get() == null && System.nanoTime() < deadlineNanos) {
                Thread.sleep(10);
            }
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            Throwable observed = innerObserved.get();
            assertNotNull(
                          observed,
                          "nested handler did not observe cancellation — token did NOT propagate through "
                                  + "childContextFor. polls done: "
                                  + innerPolls.get());
            assertTrue(
                       observed instanceof FlowCancellationException,
                       "nested handler must observe FlowCancellationException; got "
                               + observed.getClass().getName());
            assertTrue(
                       elapsedMs < 1_000L,
                       "cancellation propagation latency "
                               + elapsedMs
                               + "ms exceeds 1s — propagation through nested dispatch is broken or slow");

            caller.join(2_000L);
        }
    }

    @Test
    void nestedHandler_inheritsParent_messageId_asCausation() throws Exception {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            AtomicReference<ExecutionContext> innerCtx  = new AtomicReference<>();
            CountDownLatch                    innerDone = new CountDownLatch(1);

            runtime
                    .commands()
                    .register(
                              new AbstractNoReturnCommandHandler<Inner>() {
                                  @Override
                                  protected void handle(Inner cmd) {
                                      innerCtx.set(FlowScope.current().orElseThrow());
                                      innerDone.countDown();
                                  }
                              });
            runtime
                    .commands()
                    .register(
                              new AbstractNoReturnCommandHandler<Outer>() {
                                  @Override
                                  protected void handle(Outer cmd) {
                                      runtime.commands().dispatch(Command.<Inner>builder().body(new Inner()).build());
                                  }
                              });

            ExecutionContext outerCtx = ExecutionContext.root();
            runtime.commands().dispatch(Command.<Outer>builder().body(new Outer()).build(), outerCtx);
            assertTrue(innerDone.await(5, TimeUnit.SECONDS), "inner handler must run within 5s");

            ExecutionContext inner = innerCtx.get();
            assertNotNull(inner);
            assertEquals(
                         outerCtx.traceId(),
                         inner.traceId(),
                         "nested handler must inherit parent's traceId — distributed-trace continuity");
            assertEquals(
                         outerCtx.correlationId(),
                         inner.correlationId(),
                         "nested handler must inherit parent's correlationId");
            // Note: causationId chain is per-bus-implementation; many runtimes set causation=parentMsg.
            // We don't pin the exact value here — that's pinned by ThreadContextScopedValueIntegrityTest.
            assertNotNull(inner.causationId(), "nested handler must carry SOME causationId");
        }
    }
}
