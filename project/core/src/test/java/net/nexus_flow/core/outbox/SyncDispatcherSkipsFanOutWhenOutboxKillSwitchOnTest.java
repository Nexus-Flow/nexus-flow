package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * commit (a) — when the runtime is configured with an {@link OutboxConfig#useOutboxFanOut() outbox
 * fan-out kill-switch} set to {@code true}, the inline event bus fan-out from {@code
 * DefaultCommandBus.dispatchAndReturnResultBody} is skipped.
 *
 * <p>The synchronous listener registered for the test must therefore NOT be invoked while the
 * command runs — its later invocation is the responsibility of the{@code OutboxWorker} (covered by
 * the commit (b) tests). What we pin here is the suppression of the inline path and the presence of
 * the outbox row.
 */
class SyncDispatcherSkipsFanOutWhenOutboxKillSwitchOnTest {

    static final class Bumped extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Bumped(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Counter extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void bump() {
            recordEvent(new Bumped("ctr-kill"));
        }
    }

    record DoBump() {
    }

    @Test
    void killSwitchOn_inlineListenerNotCalled_butOutboxRowAppended() {
        Instant               t0      = Instant.parse("2026-05-19T11:00:00Z");
        Clock                 clock   = Clock.fixed(t0, ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);
        OutboxConfig          outbox  =
                OutboxConfig.builder(storage, new JavaSerializationOutboxPayloadCodec())
                        .clock(clock)
                        .useOutboxFanOut(true)
                        // Commit (a) inspects the inline path in isolation;
                        // disable the worker so the listener can only have
                        // been called synchronously (which the kill-switch
                        // forbids — that is the invariant we're pinning).
                        .autoStartWorker(false)
                        .workerPollInterval(Duration.ofMinutes(5))
                        .build();

        AtomicInteger listenerCalls = new AtomicInteger();
        try (FlowRuntime runtime = FlowRuntime.builder().outbox(outbox).build()) {

            runtime
                    .events()
                    .register(
                              new AbstractDomainEventListener<Bumped>() {
                                  @Override
                                  public void handle(Bumped event) {
                                      listenerCalls.incrementAndGet();
                                  }
                              });

            runtime
                    .commands()
                    .register(
                              new AbstractReturnCommandHandler<DoBump, String>() {
                                  @Override
                                  protected String handle(DoBump command) {
                                      Counter c = new Counter();
                                      c.bump();
                                      // Option A bridge — re-record into the
                                      // JVM-wide sink so the bus picks up the events on
                                      // its post-handler drain step (same shape as
                                      // EventOrderingByAggregateRecordingTest).
                                      c.drainEvents()
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
                                                     Command.<DoBump>builder().body(new DoBump()).build(),
                                                     ExecutionContext.root(),
                                                     ErrorPolicy.failFast());
            assertInstanceOf(DispatchResult.Success.class, r);
        }

        // The inline listener MUST NOT have been called. The outbox
        // worker is the sole publisher under the kill-switch, and the
        // poll interval was set high enough that it can't have fired
        // within the test window. (We also closed the runtime, which
        // shuts the worker down before it would.)
        assertEquals(0, listenerCalls.get(), "kill-switch ON must suppress inline fan-out");

        // AND: exactly one outbox row was appended for the single event.
        assertEquals(1, storage.size(), "outbox must carry the drained event as a row");
        OutboxRecord row = storage.snapshot().getFirst();
        assertEquals("ctr-kill:0", row.idempotencyKey().value());
        // Payload was encoded via the codec.
        assertTrue(row.payloadBytes().length > 0);
    }
}
