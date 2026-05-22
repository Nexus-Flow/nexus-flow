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
 * DROP_OLDEST evicts the head of the queue to make room for the new dispatch, and the eviction is
 * observable via a JUL WARNING log line (pinning mirrors {@code
 * DispatchAfterCloseShortCircuitsInterceptorsTest}).
 *
 * <p>Setup: concurrencyLevel=2 + queueDepth=2 with handlers blocking on a latch. We feed 6
 * dispatches; the 5th and 6th force the gate to evict the queue head twice. The handler runs at
 * most {2 in-flight + 2 most-recent} = 4 commands once we release the latch.
 */
class SaturationPolicyDropOldestTest {

    record Drop(int n) {
    }

    @Test
    void dropOldest_evictsHeadAndLogsWarning() throws Exception {
        Logger          gateLogger =
                Logger.getLogger("net.nexus_flow.core.cqrs.command.HandlerBackpressureGate");
        List<LogRecord> records    = new ArrayList<>();
        Handler         capture    =
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
        Level previous = gateLogger.getLevel();
        gateLogger.setLevel(Level.ALL);
        gateLogger.addHandler(capture);

        CountDownLatch  release  = new CountDownLatch(1);
        AtomicInteger   entered  = new AtomicInteger();
        CommandSettings settings =
                CommandSettings.builder()
                        .backpressure(new HandlerBackpressureSettings(2, SaturationPolicy.DROP_OLDEST, null))
                        .build();

        NoReturnCommandHandler<Drop> handler =
                new AbstractNoReturnCommandHandler<>() {
                    @Override
                    protected void handle(Drop command) {
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
                // Feed 6 dispatches serially. With concurrency=2, the
                // first 2 enter the handler; the next 2 fill the queue;
                // the last 2 each force an eviction.
                for (int i = 0; i < 6; i++) {
                    runtime.commands().dispatch(Command.<Drop>builder().body(new Drop(i)).build());
                    if (i == 0 || i == 1)
                        Thread.sleep(30);
                }

                // Assert the JUL warning was emitted at least twice (one
                // per eviction).
                long evictions =
                        records.stream()
                                .filter(r -> r.getLevel() == Level.WARNING)
                                .filter(r -> r.getMessage() != null && r.getMessage().contains("DROP_OLDEST"))
                                .count();
                assertTrue(
                           evictions >= 1,
                           "DROP_OLDEST: at least one eviction should have "
                                   + "produced a WARNING log line; saw "
                                   + evictions
                                   + " out of "
                                   + records.size()
                                   + " records");

                // entered must remain <= 2 (the in-flight slots) while
                // the latch is held.
                assertTrue(
                           entered.get() <= 2,
                           "Evicted dispatches must never reach the handler body; " + "entered=" + entered.get());
            } finally {
                release.countDown();
                runtime.commands().unregister(handler);
                gateLogger.removeHandler(capture);
                gateLogger.setLevel(previous);
            }
        }
    }
}
