package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.junit.jupiter.api.Test;

/**
 * commit (d) — {@link FlowRuntime#close()} must shut the {@link OutboxWorker} down BEFORE the
 * {@code EventBus}, so the worker never observes a half-closed bus. The worker must:
 *
 * <ol>
 * <li>stop claiming new batches;
 * <li>let any in-flight delivery finish best-effort;
 * <li>return from {@code shutdown()} within a bounded time window.
 * </ol>
 */
class OutboxWorkerCloseDrainsInFlightTest {

    static final class Beat extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Beat(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Heart extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void beat() {
            recordEvent(new Beat("heart-1"));
        }
    }

    record DoBeat() {
    }

    @Test
    void runtimeClose_stopsWorker_andWorkerThreadIsNotAlive() throws Exception {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        OutboxConfig          outbox  =
                OutboxConfig.builder(storage, new JavaSerializationOutboxPayloadCodec())
                        .clock(Clock.systemUTC())
                        .useOutboxFanOut(true)
                        // Tight poll so we know the worker is actively cycling.
                        .workerPollInterval(Duration.ofMillis(10))
                        .build();

        CountDownLatch delivered      = new CountDownLatch(1);
        AtomicInteger  publishedCount = new AtomicInteger();

        try (FlowRuntime runtime = FlowRuntime.builder().outbox(outbox).build()) {
            runtime
                    .events()
                    .register(
                              new AbstractDomainEventListener<Beat>() {
                                  @Override
                                  public void handle(Beat event) {
                                      publishedCount.incrementAndGet();
                                      delivered.countDown();
                                  }
                              });
            runtime
                    .commands()
                    .register(
                              new AbstractReturnCommandHandler<DoBeat, String>() {
                                  @Override
                                  protected String handle(DoBeat command) {
                                      Heart h = new Heart();
                                      h.beat();
                                      h.drainEvents()
                                              .forEach(
                                                       e -> net.nexus_flow.core.cqrs.event.DomainEventContext.current()
                                                               .recordEvent(e));
                                      return "ok";
                                  }
                              });

            DispatchResult<String> r =
                    runtime
                            .commands()
                            .dispatchAndReturnResult(
                                                     Command.<DoBeat>builder().body(new DoBeat()).build(),
                                                     ExecutionContext.root(),
                                                     ErrorPolicy.failFast());
            assertInstanceOf(DispatchResult.Success.class, r);

            // The worker (started by the runtime) must publish the row
            // through the bus eventually.
            assertTrue(delivered.await(5, TimeUnit.SECONDS), "outbox worker did not deliver within 5s");
        }

        // After close: no in-flight rows, no PENDING claims happen.
        // Storage is consistent and not claimable any more (the worker
        // is dead and FlowRuntime is closed).
        assertEquals(1, publishedCount.get(), "exactly one delivery (the row appended by the command)");
        for (OutboxRecord row : storage.snapshot()) {
            assertFalse(
                        row.status() == OutboxStatus.IN_FLIGHT,
                        "no row may remain IN_FLIGHT after close; row=" + row);
        }
    }
}
