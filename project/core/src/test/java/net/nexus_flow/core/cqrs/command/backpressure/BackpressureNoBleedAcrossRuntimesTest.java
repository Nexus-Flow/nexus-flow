package net.nexus_flow.core.cqrs.command.backpressure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.cqrs.command.*;
import net.nexus_flow.core.cqrs.command.exceptions.CommandHandlerExecutionError;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * no-bleed contract extended to back-pressure: the queue/policy of a handler registered in runtime
 * A must not influence the dispatches of the same handler type (a fresh registration) in runtime B.
 *
 * <p>Setup: two runtimes; each registers an independent handler instance for the same command type
 * with queueDepth=2 + {@link SaturationPolicy#REJECT}. We saturate runtime A while runtime B
 * remains idle. A single dispatch to runtime B MUST succeed without any saturation rejection.
 */
class BackpressureNoBleedAcrossRuntimesTest {

    record Bleed(int n) {
    }

    static NoReturnCommandHandler<Bleed> makeHandler(
            CountDownLatch latch, AtomicInteger handledCount) {
        CommandSettings settings =
                CommandSettings.builder()
                        .backpressure(new HandlerBackpressureSettings(2, SaturationPolicy.REJECT, null))
                        .build();
        return new AbstractNoReturnCommandHandler<>() {
            @Override
            protected void handle(Bleed command) {
                handledCount.incrementAndGet();
                try {
                    latch.await();
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
    }

    @Test
    void saturatingRuntimeA_doesNotImpactRuntimeB() throws Exception {
        CountDownLatch                latchA   = new CountDownLatch(1);
        CountDownLatch                latchB   = new CountDownLatch(1);
        AtomicInteger                 countA   = new AtomicInteger();
        AtomicInteger                 countB   = new AtomicInteger();
        NoReturnCommandHandler<Bleed> handlerA = makeHandler(latchA, countA);
        NoReturnCommandHandler<Bleed> handlerB = makeHandler(latchB, countB);

        try (FlowRuntime rtA = FlowRuntime.builder().build();
                FlowRuntime rtB = FlowRuntime.builder().build()) {
            rtA.commands().register(handlerA);
            rtB.commands().register(handlerB);
            try {
                // Saturate A: 1 in-flight + 2 queued = capacity. Subsequent
                // dispatch rejects.
                Thread.ofVirtual()
                        .start(
                               () -> rtA.commands().dispatch(Command.<Bleed>builder().body(new Bleed(0)).build()));
                Thread.sleep(50);
                rtA.commands().dispatch(Command.<Bleed>builder().body(new Bleed(1)).build());
                rtA.commands().dispatch(Command.<Bleed>builder().body(new Bleed(2)).build());

                boolean rejected = false;
                try {
                    rtA.commands().dispatch(Command.<Bleed>builder().body(new Bleed(3)).build());
                } catch (SaturationRejectedException e) {
                    rejected = true;
                } catch (CommandHandlerExecutionError ce) {
                    if (ce.getCause() instanceof SaturationRejectedException)
                        rejected = true;
                    else
                        throw ce;
                }
                assertTrue(rejected, "Runtime A should be saturated and reject the 4th dispatch");

                // Runtime B: idle. Single dispatch must succeed (gets into
                // in-flight slot).
                rtB.commands().dispatch(Command.<Bleed>builder().body(new Bleed(0)).build());

                // Allow handler B to start.
                long deadline = System.currentTimeMillis() + 1_000L;
                while (countB.get() < 1 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(5);
                }
                assertEquals(
                             1,
                             countB.get(),
                             "Runtime B's handler must have started; saturation in A "
                                     + "must not propagate to B (no-bleed ×)");
            } finally {
                latchA.countDown();
                latchB.countDown();
                rtA.commands().unregister(handlerA);
                rtB.commands().unregister(handlerB);
            }
        }
    }
}
