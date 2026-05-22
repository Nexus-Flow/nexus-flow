package net.nexus_flow.core.cqrs.command.backpressure;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.nexus_flow.core.cqrs.command.*;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * when a runtime is closed while there are still pending dispatches in a handler's queue, the
 * executor must:
 *
 * <ol>
 * <li>Stop running silently (existingcontract).
 * <li>Emit a JUL {@code WARNING} accounting for the items lost.
 * </ol>
 *
 * <p>Setup: concurrency=1, queueDepth=4, handler holds a latch. We fire 4 dispatches (1 in flight,
 * 3 queued), then close the runtime before releasing the latch. Pinned by capturing the NoReturn
 * executor logger output.
 */
class CloseDuringSaturationLogsLostItemsTest {

    record Lose(int n) {
    }

    @Test
    void close_warnsAboutPendingTasksLostDuringShutdown() throws Exception {
        Logger          executorLogger =
                Logger.getLogger("net.nexus_flow.core.cqrs.command.DefaultCommandHandlerExecutor");
        List<LogRecord> records        = new ArrayList<>();
        Handler         capture        =
                new Handler() {
                                                   @Override
                                                   public void publish(LogRecord record) {
                                                       records.add(record);
                                                   }

                                                   @Override
                                                   public void flush() {
                                                   }

                                                   @Override
                                                   public void close() {
                                                   }
                                               };
        capture.setLevel(Level.ALL);
        Level previous = executorLogger.getLevel();
        executorLogger.setLevel(Level.ALL);
        executorLogger.addHandler(capture);

        CountDownLatch               release  = new CountDownLatch(1);
        AtomicInteger                entered  = new AtomicInteger();
        CommandSettings              settings =
                CommandSettings.builder()
                        .backpressure(new HandlerBackpressureSettings(4, SaturationPolicy.BLOCK_CALLER, null))
                        .build();
        NoReturnCommandHandler<Lose> handler  =
                new AbstractNoReturnCommandHandler<>() {
                                                          @Override
                                                          protected void handle(Lose command) {
                                                              entered.incrementAndGet();
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

        FlowRuntime runtime = FlowRuntime.builder().build();
        runtime.commands().register(handler);
        try {
            // Fire enough dispatches so that some queue, but DO NOT exceed
            // capacity (1 in-flight + 4 queued = 5; we fire 4 to stay below).
            for (int i = 0; i < 4; i++) {
                final int n = i;
                Thread.ofVirtual()
                        .start(
                               () -> runtime.commands().dispatch(Command.<Lose>builder().body(new Lose(n)).build()));
            }
            // Allow enqueue to settle.
            long deadline = System.currentTimeMillis() + 2_000L;
            while (entered.get() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }

            // Close runtime while items are still pending — this should
            // surface a JUL WARNING with "lost".
            runtime.close();

            // The close() path on the executor emits a warning if
            // taskQueue is non-empty at shutdown.
            boolean foundWarning =
                    records.stream()
                            .filter(r -> r.getLevel() == Level.WARNING)
                            .anyMatch(r -> r.getMessage() != null && r.getMessage().contains("lost"));
            assertTrue(
                       foundWarning,
                       " close-during-saturation must emit a JUL WARNING "
                               + "mentioning lost items; records="
                               + records.size());
        } finally {
            release.countDown();
            executorLogger.removeHandler(capture);
            executorLogger.setLevel(previous);
        }
    }
}
