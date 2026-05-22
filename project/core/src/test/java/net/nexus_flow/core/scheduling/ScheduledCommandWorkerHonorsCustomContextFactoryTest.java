package net.nexus_flow.core.scheduling;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.command.AbstractNoReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.command.CommandBus;
import net.nexus_flow.core.runtime.CancellationToken;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.FlowScope;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link ScheduledCommandConfig.Builder#contextFactory} SPI.
 *
 * <p>The factory is the wiring point for use cases such as distributed-tracing context inheritance
 * from an external cron scheduler — the §11 audit O3 observation called out that the worker used to
 * construct random IDs inline, with no extension point. Now operators inject a custom factory via
 * the builder.
 *
 * <p>Contract verified here:
 *
 * <ul>
 * <li>Custom factory is invoked once per dispatch attempt.
 * <li>The factory receives the actual {@link ScheduledCommandRecord} and the worker-lifetime
 * {@link CancellationToken}, allowing it to embed both into the produced context.
 * <li>The context produced by the factory is the EXACT context observable via {@link
 * FlowScope#current()} inside the handler.
 * <li>The factory rejects {@code null} when supplied to the builder.
 * <li>The default factory ({@link ScheduledCommandConfig#DEFAULT_CONTEXT_FACTORY}) embeds the
 * record id and attempt as attributes.
 * </ul>
 */
class ScheduledCommandWorkerHonorsCustomContextFactoryTest {

    record Beat(String id) {
    }

    @Test
    void customContextFactory_isInvokedAndItsContextIsBoundInHandler() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            CommandBus                           bus      = runtime.commands();
            AtomicReference<ExecutionContext>    observed = new AtomicReference<>();
            AbstractNoReturnCommandHandler<Beat> handler  =
                    new AbstractNoReturnCommandHandler<>() {
                                                                      @Override
                                                                      protected void handle(Beat cmd) {
                                                                          observed.set(FlowScope.current().orElseThrow());
                                                                      }
                                                                  };
            bus.register(handler);

            try {
                Instant                         t0      = Instant.parse("2030-01-01T00:00:00Z");
                Clock                           clock   = Clock.fixed(t0, ZoneOffset.UTC);
                InMemoryScheduledCommandStorage storage = new InMemoryScheduledCommandStorage();

                // Custom factory: tag the context with a unique attribute so the test can prove
                // the handler observed exactly this factory's output, not the default one.
                AtomicInteger                           factoryCalls = new AtomicInteger();
                AtomicReference<ScheduledCommandRecord> seenRecord   = new AtomicReference<>();
                AtomicReference<CancellationToken>      seenToken    = new AtomicReference<>();
                ScheduledDispatchContextFactory         factory      =
                        (record, token) -> {
                                                                                 factoryCalls.incrementAndGet();
                                                                                 seenRecord.set(record);
                                                                                 seenToken.set(token);
                                                                                 return new ExecutionContext(
                                                                                         MessageId.random(),
                                                                                         TraceId.random(),
                                                                                         CorrelationId.random(),
                                                                                         CausationId.ROOT,
                                                                                         null,
                                                                                         null,
                                                                                         null,
                                                                                         token,
                                                                                         Map.of(
                                                                                                "custom.factory.marker",
                                                                                                "scheduled-worker-context-factory-test",
                                                                                                "scheduled.commandId",
                                                                                                record.id()));
                                                                             };

                ScheduledCommandConfig cfg =
                        ScheduledCommandConfig.builder(storage)
                                .clock(clock)
                                .pollInterval(Duration.ofMillis(50))
                                .contextFactory(factory)
                                .build();

                try (ScheduledCommandWorker worker = new ScheduledCommandWorker(cfg, bus)) {
                    Command<Beat>      cmd = Command.<Beat>builder().body(new Beat("b-1")).build();
                    ScheduledCommandId id  = ScheduledCommandId.random();
                    storage.schedule(ScheduledCommandRecord.create(id, cmd, t0, t0));

                    assertEquals(1, worker.drainOnce());

                    assertEquals(1, factoryCalls.get(), "custom factory must be invoked exactly once");
                    ScheduledCommandRecord r = seenRecord.get();
                    assertNotNull(r);
                    assertEquals(id, r.id(), "factory must receive the actual record id");
                    assertNotNull(seenToken.get(), "factory must receive a non-null worker token");

                    ExecutionContext seen = observed.get();
                    assertNotNull(seen, "handler must observe the custom context");
                    assertEquals(
                                 "scheduled-worker-context-factory-test",
                                 seen.attributes().get("custom.factory.marker"),
                                 "handler MUST observe the EXACT context returned by the custom factory — "
                                         + "not the default factory's output");
                    assertSame(
                               seenToken.get(),
                               seen.cancellation(),
                               "handler's observable context must carry the same CancellationToken the factory"
                                       + " used");
                }
            } finally {
                bus.unregister(handler);
            }
        }
    }

    @Test
    void defaultFactory_embedsCommandIdAndAttemptAsAttributes() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            CommandBus                           bus      = runtime.commands();
            AtomicReference<ExecutionContext>    observed = new AtomicReference<>();
            AbstractNoReturnCommandHandler<Beat> handler  =
                    new AbstractNoReturnCommandHandler<>() {
                                                                      @Override
                                                                      protected void handle(Beat cmd) {
                                                                          observed.set(FlowScope.current().orElseThrow());
                                                                      }
                                                                  };
            bus.register(handler);

            try {
                Instant                         t0      = Instant.parse("2030-01-01T00:00:00Z");
                Clock                           clock   = Clock.fixed(t0, ZoneOffset.UTC);
                InMemoryScheduledCommandStorage storage = new InMemoryScheduledCommandStorage();
                ScheduledCommandConfig          cfg     =
                        ScheduledCommandConfig.builder(storage)
                                .clock(clock)
                                .pollInterval(Duration.ofMillis(50))
                                .build(); // default factory
                try (ScheduledCommandWorker worker = new ScheduledCommandWorker(cfg, bus)) {
                    Command<Beat>      cmd = Command.<Beat>builder().body(new Beat("b-2")).build();
                    ScheduledCommandId id  = ScheduledCommandId.random();
                    storage.schedule(ScheduledCommandRecord.create(id, cmd, t0, t0));

                    worker.drainOnce();

                    ExecutionContext seen = observed.get();
                    assertNotNull(seen);
                    assertEquals(
                                 id,
                                 seen.attributes().get("scheduled.commandId"),
                                 "default factory must embed the record id under \"scheduled.commandId\"");
                    assertTrue(
                               seen.attributes().containsKey("scheduled.attempt"),
                               "default factory must embed the attempt count under \"scheduled.attempt\"");
                }
            } finally {
                bus.unregister(handler);
            }
        }
    }

    @Test
    void nullFactory_isRejectedByBuilder() {
        InMemoryScheduledCommandStorage storage = new InMemoryScheduledCommandStorage();
        assertThrows(
                     NullPointerException.class,
                     () -> ScheduledCommandConfig.builder(storage).contextFactory(null));
    }
}
