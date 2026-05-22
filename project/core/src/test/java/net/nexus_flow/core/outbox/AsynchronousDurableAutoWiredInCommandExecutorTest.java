package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import net.nexus_flow.core.cqrs.command.AbstractNoReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.command.CommandSettings;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.ExecutionMode;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.junit.jupiter.api.Test;

/**
 * closeout — durable mode is honoured by both command executors without callers having to invoke
 * {@link DurableDispatch#acceptAndAppend} explicitly.
 *
 * <p>Pins three invariants:
 *
 * <ol>
 * <li><strong>{@link AbstractReturnCommandHandler} + durable + outbox</strong> → events recorded
 * by the aggregate are appended to the outbox during the post-handler drain; the command
 * resolves to {@link DispatchResult.Success}.
 * <li><strong>{@link AbstractNoReturnCommandHandler} + durable + outbox</strong> → same
 * outbox-append guarantee for the fire-and-forget executor (NoReturn path), proving that the
 * unification through {@code HandlerEventDrain} reaches both executors.
 * <li><strong>Durable handler + no outbox</strong> → the strategy resolver fails fast with a
 * self-describing {@link IllegalStateException}, surfaced to the caller as {@link
 * DispatchResult.Failure}. There is no silent degradation to non-durable Inline execution.
 * </ol>
 *
 * <p>The JVM-wide {@link net.nexus_flow.core.cqrs.event.DomainEventContext} bridge is used as the
 * recording surface so the test focuses on the executor wiring rather than on the runtime sink
 * machinery.
 */
class AsynchronousDurableAutoWiredInCommandExecutorTest {

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
            recordEvent(new Bumped("agg-durable-auto"));
        }
    }

    record DoBump() {
    }

    /**
     * Single source of truth for the durable command-handler settings used by every test in this
     * fixture.
     */
    private static CommandSettings durableSettings() {
        return CommandSettings.builder().executionMode(ExecutionMode.asynchronousDurable()).build();
    }

    /** Build a fresh outbox config with deterministic clock and the worker disabled. */
    private static OutboxConfig newOutboxBoundConfig(InMemoryOutboxStorage storage, Clock clock) {
        return OutboxConfig.builder(storage, new JavaSerializationOutboxPayloadCodec())
                .clock(clock)
                .useOutboxFanOut(true)
                .autoStartWorker(false)
                .build();
    }

    /**
     * Bridge an aggregate's drained events into the JVM-wide {@link
     * net.nexus_flow.core.cqrs.event.DomainEventContext} sink.
     */
    private static void publishViaLegacyHolder(Counter c) {
        c.drainEvents()
                .forEach(e -> net.nexus_flow.core.cqrs.event.DomainEventContext.current().recordEvent(e));
    }

    // ----------------------------------------------------------------
    // Invariant 1 — Return executor: durable + outbox → append.
    // ----------------------------------------------------------------

    @Test
    void durableReturnHandler_withOutbox_appendsToOutbox_andReturnsSuccess() {
        Clock                 clock   = Clock.fixed(Instant.parse("2026-05-19T12:00:00Z"), ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        try (FlowRuntime runtime =
                FlowRuntime.builder().outbox(newOutboxBoundConfig(storage, clock)).build()) {

            runtime
                    .commands()
                    .register(
                              new AbstractReturnCommandHandler<DoBump, String>() {
                                  @Override
                                  protected String handle(DoBump command) {
                                      Counter c = new Counter();
                                      c.bump();
                                      publishViaLegacyHolder(c);
                                      return "ok";
                                  }

                                  @Override
                                  public CommandSettings getCommandSettings() {
                                      return durableSettings();
                                  }
                              });

            DispatchResult<String> result =
                    runtime
                            .commands()
                            .dispatchAndReturnResult(
                                                     Command.<DoBump>builder().body(new DoBump()).build(),
                                                     ExecutionContext.root(),
                                                     ErrorPolicy.failFast());

            assertInstanceOf(DispatchResult.Success.class, result);
            assertEquals(
                         1, storage.size(), "Durable Return-handler must append the drained event to the outbox.");
        }
    }

    // ----------------------------------------------------------------
    // Invariant 2 — NoReturn executor parity: durable + outbox → append.
    //
    // Codex's first cut only proved the Return path. The unification
    // through {@code HandlerEventDrain} must reach the NoReturn
    // executor too; this test pins that.
    // ----------------------------------------------------------------

    @Test
    void durableNoReturnHandler_withOutbox_appendsToOutbox() {
        Clock                 clock   = Clock.fixed(Instant.parse("2026-05-19T12:00:00Z"), ZoneOffset.UTC);
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage(clock);

        try (FlowRuntime runtime =
                FlowRuntime.builder().outbox(newOutboxBoundConfig(storage, clock)).build()) {

            runtime
                    .commands()
                    .register(
                              new AbstractNoReturnCommandHandler<DoBump>() {
                                  @Override
                                  protected void handle(DoBump command) {
                                      Counter c = new Counter();
                                      c.bump();
                                      publishViaLegacyHolder(c);
                                  }

                                  @Override
                                  public CommandSettings getCommandSettings() {
                                      return durableSettings();
                                  }
                              });

            runtime.commands().dispatch(Command.<DoBump>builder().body(new DoBump()).build());

            assertEquals(
                         1,
                         storage.size(),
                         "Durable NoReturn-handler must also append through HandlerEventDrain.");
        }
    }

    // ----------------------------------------------------------------
    // Invariant 3 — Durable without outbox fails fast at resolve time.
    // ----------------------------------------------------------------

    @Test
    void durableExecutionMode_withoutOutbox_returnsFailureWithSelfDescribingCause() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            runtime
                    .commands()
                    .register(
                              new AbstractReturnCommandHandler<DoBump, String>() {
                                  @Override
                                  protected String handle(DoBump command) {
                                      Counter c = new Counter();
                                      c.bump();
                                      publishViaLegacyHolder(c);
                                      return "ok";
                                  }

                                  @Override
                                  public CommandSettings getCommandSettings() {
                                      return durableSettings();
                                  }
                              });

            DispatchResult<String> result =
                    runtime
                            .commands()
                            .dispatchAndReturnResult(
                                                     Command.<DoBump>builder().body(new DoBump()).build(),
                                                     ExecutionContext.root(),
                                                     ErrorPolicy.failFast());

            @SuppressWarnings("unchecked") DispatchResult.Failure<String> failure =
                    assertInstanceOf(DispatchResult.Failure.class, result);
            Throwable                                                     cause   = failure.cause();
            String                                                        msg     = cause.getMessage();
            assertTrue(
                       msg != null && msg.contains("outbox"),
                       "Failure cause must name 'outbox' so callers can act on it: " + msg);
            assertTrue(
                       msg.contains("AsynchronousDurable"),
                       "Failure cause must name the offending mode: " + msg);
        }
    }
}
