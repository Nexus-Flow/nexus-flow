package net.nexus_flow.core.scheduling;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.command.AbstractNoReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.command.CommandBus;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.FlowScope;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import org.junit.jupiter.api.Test;

/**
 * Pins the invariant that {@link ScheduledCommandWorker#shutdown()} cooperatively cancels any
 * in-flight command handler the worker's daemon thread is currently running.
 *
 * <p>Historically the worker dispatched naked: it called {@code commandBus.dispatch(command)} (the
 * fire-and-forget overload, which does NOT accept an {@link ExecutionContext}), so any cancellation
 * primitive owned by the worker had no way to reach the handler. {@code shutdown()} only did {@link
 * Thread#interrupt()} + {@link Thread#join(long)}, which left handlers polling {@code
 * ctx.throwIfCancelledOrExpired()} unaware of cancellation.
 *
 * <p>The current implementation owns a worker-lifetime {@link
 * net.nexus_flow.core.runtime.CancellationToken} and wraps every {@code commandBus.dispatch} call
 * in {@link FlowScope#runWithContext(ExecutionContext, Runnable)} carrying that token. The {@code
 * DefaultCommandHandlerExecutor} reads {@code FlowScope.current()} at execute time, so the binding
 * propagates into the handler's context. {@code shutdown()} cancels the token <em>before</em>
 * {@link Thread#interrupt()}, so handlers polling the context observe cooperative cancellation.
 *
 * <p>Cancellation remains cooperative — handlers that poll neither the context nor interruption
 * cannot be force-stopped. {@code thread.join(5_000)} is the upper bound on the wait.
 */
class ScheduledCommandWorkerCancelsInFlightDispatchOnShutdownTest {

    record Ping(String id) {
    }

    @Test
    void shutdown_cancelsInFlightHandlerWithinGraceWindow() throws Exception {
        Instant t0    = Instant.parse("2026-05-23T16:00:00Z");
        Clock   clock = Clock.fixed(t0, ZoneOffset.UTC);

        CountDownLatch             handlerEntered = new CountDownLatch(1);
        AtomicReference<Throwable> observed       = new AtomicReference<>();

        AbstractNoReturnCommandHandler<Ping> blockingHandler =
                new AbstractNoReturnCommandHandler<>() {
                    @Override
                    public boolean isSagaEnabled() {
                        // Inline saga execution: the handler runs on the worker's daemon thread directly,
                        // so cancellation of the worker token must propagate without an executor hop.
                        return true;
                    }

                    @Override
                    protected void handle(Ping command) {
                        handlerEntered.countDown();
                        ExecutionContext ctx   = FlowScope.current().orElseThrow();
                        long     deadlineNanos = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                        try {
                            while (System.nanoTime() < deadlineNanos) {
                                ctx.throwIfCancelledOrExpired();
                                try {
                                    Thread.sleep(20);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    observed.set(ie);
                                    throw new RuntimeException(ie);
                                }
                            }
                            // Safety exit; assertions below will catch the regression.
                        } catch (FlowCancellationException fce) {
                            observed.set(fce);
                            throw fce;
                        }
                    }
                };

        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            CommandBus bus = runtime.commands();
            bus.register(blockingHandler);

            InMemoryScheduledCommandStorage storage = new InMemoryScheduledCommandStorage();
            ScheduledCommandConfig          config  =
                    ScheduledCommandConfig.builder(storage)
                            .clock(clock)
                            .pollInterval(Duration.ofMillis(10))
                            .batchSize(1)
                            .build();
            ScheduledCommandWorker          worker  = new ScheduledCommandWorker(config, bus);

            // Schedule a single command due immediately.
            Command<Ping>      cmd = Command.<Ping>builder().body(new Ping("p-cancel")).build();
            ScheduledCommandId id  = ScheduledCommandId.random();
            storage.schedule(ScheduledCommandRecord.create(id, cmd, t0, t0));

            try {
                worker.start();

                assertTrue(
                           handlerEntered.await(5, TimeUnit.SECONDS),
                           "scheduled worker did not enter handler within 5s — scenario broken");

                long t1 = System.nanoTime();
                worker.shutdown();
                long elapsedMs = (System.nanoTime() - t1) / 1_000_000L;

                assertTrue(
                           elapsedMs < 2_000L,
                           "shutdown() took "
                                   + elapsedMs
                                   + "ms; expected < 2s (5s join grace would be regression)");

                Throwable seen = observed.get();
                assertNotNull(
                              seen,
                              "handler did not observe cancellation or interruption — shutdown is not cooperative");
                assertTrue(
                           seen instanceof FlowCancellationException || seen instanceof InterruptedException,
                           "handler observed unexpected throwable type: " + seen.getClass().getName());

                assertFalse(worker.isRunning(), "worker reports running after shutdown()");
            } finally {
                worker.shutdown();
            }
        }
    }
}
