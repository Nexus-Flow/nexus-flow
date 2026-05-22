package net.nexus_flow.core.cqrs.command.backpressure;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import net.nexus_flow.core.cqrs.command.*;
import net.nexus_flow.core.cqrs.command.exceptions.CommandHandlerExecutionError;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * REJECT policy fast-fails synchronously when the handler is unable to accept the dispatch
 * immediately.
 *
 * <p>Setup: concurrencyLevel=1, queueDepth=0. While the in-flight handler holds a latch, any
 * further dispatch must surface a {@link SaturationRejectedException} in less than 5 ms wall-clock.
 * The deadline pins the "no blocking, no retries" contract — without which a misbehaving Outbox
 * drain would hot-loop on the rejection.
 */
class SaturationPolicyRejectFastFailsImmediatelyTest {

    record Fast(int n) {
    }

    @Test
    void rejectPolicy_surfacesSaturationRejectedExceptionWithin5Ms() throws Exception {
        CountDownLatch  release  = new CountDownLatch(1);
        CountDownLatch  inflight = new CountDownLatch(1);
        CommandSettings settings =
                CommandSettings.builder()
                        .backpressure(new HandlerBackpressureSettings(0, SaturationPolicy.REJECT, null))
                        .build();

        NoReturnCommandHandler<Fast> handler =
                new AbstractNoReturnCommandHandler<>() {
                    @Override
                    protected void handle(Fast command) {
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
                // Send the first dispatch on a separate thread so it
                // can occupy the in-flight slot.
                Thread.ofVirtual()
                        .start(
                               () -> runtime.commands().dispatch(Command.<Fast>builder().body(new Fast(0)).build()));
                assertTrue(
                           inflight.await(2, java.util.concurrent.TimeUnit.SECONDS),
                           "The first dispatch should have entered the handler");

                // Next dispatch must reject within 5 ms.
                long                        t0       = System.nanoTime();
                SaturationRejectedException surfaced = null;
                try {
                    runtime.commands().dispatch(Command.<Fast>builder().body(new Fast(1)).build());
                } catch (SaturationRejectedException rej) {
                    surfaced = rej;
                } catch (CommandHandlerExecutionError wrapped) {
                    if (wrapped.getCause() instanceof SaturationRejectedException rej) {
                        surfaced = rej;
                    } else {
                        throw wrapped;
                    }
                }
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

                assertNotNull(
                              surfaced,
                              "REJECT: a saturated dispatch MUST surface "
                                      + "SaturationRejectedException synchronously");
                assertTrue(
                           elapsedMs < 5,
                           "REJECT: fast-fail wall-clock budget is <5ms; " + "took " + elapsedMs + "ms");
            } finally {
                release.countDown();
                runtime.commands().unregister(handler);
            }
        }
    }
}
