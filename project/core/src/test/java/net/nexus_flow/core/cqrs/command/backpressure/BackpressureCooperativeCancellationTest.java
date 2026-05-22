package net.nexus_flow.core.cqrs.command.backpressure;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.command.*;
import net.nexus_flow.core.cqrs.command.exceptions.CommandHandlerExecutionError;
import net.nexus_flow.core.runtime.CancellationToken;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.FlowScope;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import org.junit.jupiter.api.Test;

/**
 * cooperative cancellation while a caller is parked inside {@link SaturationPolicy#BLOCK_CALLER}.
 *
 * <p>Setup: a handler with concurrencyLevel=1 and queueDepth=0 + blockTimeout=100ms. A first
 * dispatch occupies the slot. A second dispatch is fired on a separate thread under a context with
 * its own {@link CancellationToken}; that thread parks in the gate's BLOCK_CALLER loop. We cancel
 * the token mid-park, and the loop must surface {@link FlowCancellationException} <em>before</em>
 * the 100 ms timeout fires.
 */
class BackpressureCooperativeCancellationTest {

    record Coop(int n) {
    }

    @Test
    void blockCaller_observesCancellationBeforeTimeoutElapses() throws Exception {
        CountDownLatch  release  = new CountDownLatch(1);
        CountDownLatch  inflight = new CountDownLatch(1);
        CommandSettings settings =
                CommandSettings.builder()
                        .backpressure(
                                      new HandlerBackpressureSettings(
                                              0, SaturationPolicy.BLOCK_CALLER, Duration.ofMillis(100)))
                        .build();

        NoReturnCommandHandler<Coop> handler =
                new AbstractNoReturnCommandHandler<>() {
                    @Override
                    protected void handle(Coop command) {
                        inflight.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    @Override
                    public int getConcurrencyLevel() {
                        return 1;
                    }

                    @Override
                    public InitializationType getInitializationType() {
                        return InitializationType.EAGER;
                    }

                    @Override
                    public CommandSettings getCommandSettings() {
                        return settings;
                    }
                };

        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            runtime.commands().register(handler);
            try {
                // Occupy the only slot.
                Thread.ofVirtual()
                        .start(
                               () -> runtime.commands().dispatch(Command.<Coop>builder().body(new Coop(0)).build()));
                assertTrue(inflight.await(2, java.util.concurrent.TimeUnit.SECONDS));

                ExecutionContext ctx =
                        new ExecutionContext(
                                MessageId.random(),
                                TraceId.random(),
                                CorrelationId.random(),
                                CausationId.ROOT,
                                null,
                                null,
                                Instant.now().plusSeconds(60),
                                CancellationToken.create(),
                                Map.of());

                AtomicReference<Throwable> surfaced = new AtomicReference<>();
                Thread                     blocked  =
                        Thread.ofVirtual()
                                .start(
                                       () -> FlowScope.runWithContext(
                                                                      ctx,
                                                                      () -> {
                                                                          try {
                                                                              runtime
                                                                                      .commands()
                                                                                      .dispatch(Command.<Coop>builder().body(new Coop(1))
                                                                                              .build());
                                                                          } catch (Throwable t) {
                                                                              surfaced.set(t);
                                                                          }
                                                                      }));

                // Let the second dispatch enter the BLOCK_CALLER park.
                Thread.sleep(30);
                long t0 = System.nanoTime();
                ctx.cancellation().cancel();
                blocked.join(1_000);
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

                Throwable t = surfaced.get();
                assertNotNull(t, " cancelled BLOCK_CALLER must surface a throwable");
                boolean isCancellation =
                        t instanceof FlowCancellationException || (t instanceof CommandHandlerExecutionError ce && ce
                                .getCause() instanceof FlowCancellationException);
                assertTrue(
                           isCancellation, " surfaced throwable must be FlowCancellationException; got " + t);
                assertTrue(
                           elapsedMs < 100,
                           " cooperative cancellation must beat the "
                                   + "100ms blockTimeout; observed "
                                   + elapsedMs
                                   + "ms");
            } finally {
                release.countDown();
                runtime.commands().unregister(handler);
            }
        }
    }
}
