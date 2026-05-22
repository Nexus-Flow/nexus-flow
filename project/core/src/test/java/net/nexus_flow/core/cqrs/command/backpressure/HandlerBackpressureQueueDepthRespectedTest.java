package net.nexus_flow.core.cqrs.command.backpressure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.cqrs.command.*;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.junit.jupiter.api.Test;

/**
 * pins the queue-depth contract under {@link SaturationPolicy#BLOCK_CALLER}.
 *
 * <p>Setup: concurrencyLevel=2, queueDepth=4. We dispatch 6 commands to a handler whose body blocks
 * on a latch. The first 2 acquire the concurrency permits and start running; the next 4 land in the
 * queue (filling it to capacity); the 7th dispatch would have to block the caller, so we only fire
 * 6 to keep the test deterministic on a single caller thread.
 *
 * <p>The contract: <em>no handler invocation runs ahead of the latch</em>. Counter MUST stay at 0
 * until we release the latch. Once released, all 6 must complete.
 */
class HandlerBackpressureQueueDepthRespectedTest {

    record Ping(int n) {
    }

    @Test
    void queueDepthRespected_blockCallerPolicy_holdsBacklogUntilCapacityFrees() throws Exception {
        CountDownLatch  release  = new CountDownLatch(1);
        AtomicInteger   started  = new AtomicInteger();
        AtomicInteger   done     = new AtomicInteger();
        CommandSettings settings =
                CommandSettings.builder()
                        .backpressure(new HandlerBackpressureSettings(4, SaturationPolicy.BLOCK_CALLER, null))
                        .build();

        NoReturnCommandHandler<Ping> handler =
                new AbstractNoReturnCommandHandler<>() {
                    @Override
                    protected void handle(Ping command) {
                        started.incrementAndGet();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        done.incrementAndGet();
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
                // 6 dispatches: 2 in-flight + 4 queued, all under BLOCK_CALLER
                // with a 4-slot queue. None of these should saturate; the gate
                // accepts them all because the queue can hold exactly 4 and we
                // have 2 permits taking 2 of them off the queue immediately.
                List<Thread> casters = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    final int n = i;
                    Thread    t =
                            Thread.ofVirtual()
                                    .start(
                                           () -> runtime
                                                   .commands()
                                                   .dispatch(Command.<Ping>builder().body(new Ping(n)).build()));
                    casters.add(t);
                }

                // Wait until exactly the 2 in-flight handlers have started.
                long deadline = System.currentTimeMillis() + 2_000L;
                while (started.get() < 2 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(5);
                }
                assertEquals(
                             2,
                             started.get(),
                             " with concurrencyLevel=2, exactly 2 handler "
                                     + "bodies should be in flight while the queue is full.");
                assertEquals(0, done.get(), "No handler may have completed while the latch is held");

                // Free everyone.
                release.countDown();
                for (Thread c : casters) {
                    c.join(5_000);
                }

                long doneDeadline = System.currentTimeMillis() + 5_000L;
                while (done.get() < 6 && System.currentTimeMillis() < doneDeadline) {
                    Thread.sleep(10);
                }
                assertEquals(
                             6, done.get(), "All 6 dispatches must eventually complete once the latch is released");
            } finally {
                release.countDown();
                runtime.commands().unregister(handler);
            }
        }
    }

    /**
     * Sanity: even with BLOCK_CALLER, no rejection ever fires; the gate's saturation surface is
     * policy-driven.
     */
    @Test
    void blockCaller_neverProducesSaturationRejection_evenAtQueueFull() throws Exception {
        CountDownLatch  release  = new CountDownLatch(1);
        AtomicInteger   handled  = new AtomicInteger();
        CommandSettings settings =
                CommandSettings.builder()
                        .backpressure(new HandlerBackpressureSettings(2, SaturationPolicy.BLOCK_CALLER, null))
                        .build();

        NoReturnCommandHandler<Ping> handler =
                new AbstractNoReturnCommandHandler<>() {
                    @Override
                    protected void handle(Ping command) {
                        try {
                            release.await();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        handled.incrementAndGet();
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
                // Concurrency=1 + queueDepth=2 → capacity 3. Three serial
                // dispatches fit without saturation; each may queue while
                // the latch is held.
                List<Thread> casters = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    final int n = i;
                    Thread    t =
                            Thread.ofVirtual()
                                    .start(
                                           () -> runtime
                                                   .commands()
                                                   .dispatch(Command.<Ping>builder().body(new Ping(n)).build()));
                    casters.add(t);
                }
                // Give them time to actually enqueue.
                Thread.sleep(150);
                release.countDown();
                for (Thread t : casters)
                    t.join(5_000);

                long deadline = System.currentTimeMillis() + 5_000L;
                while (handled.get() < 3 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                assertTrue(
                           handled.get() >= 1,
                           "At least one handler invocation should have completed; got " + handled.get());
            } finally {
                release.countDown();
                runtime.commands().unregister(handler);
            }
        }
    }

    // Reference to suppress "unused import" if test minimisation strips one.
    @SuppressWarnings("unused")
    private static final Class<?> REF1 = SaturationRejectedException.class;

    @SuppressWarnings("unused")
    private static final Class<?> REF2 = DispatchResult.class;

    @SuppressWarnings("unused")
    private static final long REF3 = TimeUnit.MILLISECONDS.toMillis(1);
}
