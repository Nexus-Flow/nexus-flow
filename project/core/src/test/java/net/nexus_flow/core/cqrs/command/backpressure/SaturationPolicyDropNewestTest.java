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
 * DROP_NEWEST surfaces a {@link SaturationRejectedException} for dispatches that arrive once the
 * queue is saturated.
 *
 * <p>Setup: concurrencyLevel=2 + queueDepth=2 (total capacity 4). 6 dispatches are fired serially
 * from the same caller thread; the first 4 enqueue cleanly, the last 2 surface a rejection. None of
 * the rejected dispatches must ever reach the handler body.
 */
class SaturationPolicyDropNewestTest {

    record Bip(int n) {
    }

    @Test
    void dropNewest_rejectsExcessDispatchesAndPreservesQueuedOnes() throws Exception {
        CountDownLatch  release  = new CountDownLatch(1);
        AtomicInteger   entered  = new AtomicInteger();
        CommandSettings settings =
                CommandSettings.builder()
                        .backpressure(new HandlerBackpressureSettings(2, SaturationPolicy.DROP_NEWEST, null))
                        .build();

        NoReturnCommandHandler<Bip> handler =
                new AbstractNoReturnCommandHandler<>() {
                    @Override
                    protected void handle(Bip command) {
                        entered.incrementAndGet();
                        try {
                            release.await();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    @Override
                    public int getConcurrencyLevel() {
                        return 2;
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
                int accepted = 0;
                int rejected = 0;
                // Fire serially so we don't race the in-flight slots
                // (two virtual threads might enqueue simultaneously and
                // both succeed; we want a deterministic accept/reject
                // pattern).
                for (int i = 0; i < 6; i++) {
                    try {
                        runtime.commands().dispatch(Command.<Bip>builder().body(new Bip(i)).build());
                        accepted++;
                    } catch (SaturationRejectedException rej) {
                        rejected++;
                        assertEquals(SaturationPolicy.DROP_NEWEST, rej.policy());
                    } catch (CommandHandlerExecutionError wrapped) {
                        if (wrapped.getCause() instanceof SaturationRejectedException rej) {
                            rejected++;
                            assertEquals(SaturationPolicy.DROP_NEWEST, rej.policy());
                        } else {
                            throw wrapped;
                        }
                    }
                    // Tiny pause so the eager workers have a chance to
                    // pick up the in-flight slot before the next dispatch
                    // attempts to enqueue.
                    if (i == 0 || i == 1)
                        Thread.sleep(30);
                }

                // 2 in-flight + 2 queued = 4 accepted; 2 rejected.
                assertEquals(
                             4,
                             accepted,
                             "DROP_NEWEST: with concurrency=2+queueDepth=2 "
                                     + "exactly 4 dispatches should be accepted; got "
                                     + accepted);
                assertEquals(
                             2,
                             rejected,
                             "DROP_NEWEST: exactly 2 dispatches should be "
                                     + "rejected via SaturationRejectedException; got "
                                     + rejected);

                // The handler body must NOT have started more than the
                // 2 in-flight commands while the latch is held.
                assertTrue(
                           entered.get() <= 2,
                           "Rejected dispatches must never reach the handler body; " + "entered=" + entered.get());
            } finally {
                release.countDown();
                runtime.commands().unregister(handler);
            }
        }
    }
}
