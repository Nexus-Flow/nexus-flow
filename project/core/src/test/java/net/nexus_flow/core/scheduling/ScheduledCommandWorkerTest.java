package net.nexus_flow.core.scheduling;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.command.AbstractNoReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.command.CommandBus;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link ScheduledCommandWorker} happy path and failure/retry/terminal scenarios for
 * scheduled command dispatch.
 */
class ScheduledCommandWorkerTest {

    record Ping(String id) {
    }

    /** A frozen clock the test can tick forward manually. */
    static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant start) {
            this.now = start;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        void setTo(Instant i) {
            now = i;
        }
    }

    @Test
    void due_record_dispatches_exactlyOnce() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            CommandBus              bus      = runtime.commands();
            AtomicInteger           seen     = new AtomicInteger();
            AtomicReference<String> lastBody = new AtomicReference<>();
            var                     handler  =
                    new AbstractNoReturnCommandHandler<Ping>() {
                                                         @Override
                                                         public boolean isSagaEnabled() {
                                                             return true;
                                                         }

                                                         @Override
                                                         protected void handle(Ping command) {
                                                             seen.incrementAndGet();
                                                             lastBody.set(command.id());
                                                         }
                                                     };
            bus.register(handler);
            try {
                Instant                         t0      = Instant.parse("2030-01-01T00:00:00Z");
                TestClock                       clock   = new TestClock(t0);
                InMemoryScheduledCommandStorage storage = new InMemoryScheduledCommandStorage();
                ScheduledCommandConfig          cfg     =
                        ScheduledCommandConfig.builder(storage)
                                .clock(clock)
                                .pollInterval(Duration.ofMillis(50))
                                .build();
                try (ScheduledCommandWorker worker = new ScheduledCommandWorker(cfg, bus)) {
                    Command<Ping>      cmd = Command.<Ping>builder().body(new Ping("p-1")).build();
                    ScheduledCommandId id  = ScheduledCommandId.random();
                    storage.schedule(
                                     ScheduledCommandRecord.create(id, cmd, t0.plus(Duration.ofMinutes(5)), t0));

                    // Not due yet → drainOnce processes nothing.
                    assertEquals(0, worker.drainOnce());
                    assertEquals(0, seen.get());

                    // Advance past fireAt and drain.
                    clock.advance(Duration.ofMinutes(6));
                    assertEquals(1, worker.drainOnce());
                    assertEquals(1, seen.get());
                    assertEquals("p-1", lastBody.get());

                    var stored = storage.find(id).orElseThrow();
                    assertEquals(ScheduledCommandStatus.DISPATCHED, stored.status());
                    assertEquals(1, stored.attempt());

                    // A second drain must NOT re-dispatch.
                    assertEquals(0, worker.drainOnce());
                    assertEquals(1, seen.get());
                }
            } finally {
                bus.unregister(handler);
            }
        }
    }

    @Test
    void transient_failure_reschedulesWithBackoff() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            CommandBus    bus     = runtime.commands();
            AtomicInteger seen    = new AtomicInteger();
            var           handler =
                    new AbstractNoReturnCommandHandler<Ping>() {
                                              @Override
                                              public boolean isSagaEnabled() {
                                                  return true;
                                              }

                                              @Override
                                              protected void handle(Ping command) {
                                                  int attempt = seen.incrementAndGet();
                                                  if (attempt < 3) {
                                                      throw new IllegalStateException("transient #" + attempt);
                                                  }
                                              }
                                          };
            bus.register(handler);
            try {
                Instant                         t0      = Instant.parse("2030-01-01T00:00:00Z");
                TestClock                       clock   = new TestClock(t0);
                InMemoryScheduledCommandStorage storage = new InMemoryScheduledCommandStorage();
                ScheduledCommandConfig          cfg     =
                        ScheduledCommandConfig.builder(storage)
                                .clock(clock)
                                .pollInterval(Duration.ofMillis(50))
                                .maxAttempts(5)
                                .backoffBase(Duration.ofSeconds(1))
                                .backoffMax(Duration.ofMinutes(10))
                                .build();
                try (ScheduledCommandWorker worker = new ScheduledCommandWorker(cfg, bus)) {
                    ScheduledCommandId id = ScheduledCommandId.random();
                    storage.schedule(
                                     ScheduledCommandRecord.create(
                                                                   id, Command.<Ping>builder().body(new Ping("p")).build(), t0, t0));

                    // Attempt 1 → failure → rescheduled at t0 + 1s.
                    assertEquals(1, worker.drainOnce());
                    var r = storage.find(id).orElseThrow();
                    assertEquals(ScheduledCommandStatus.PENDING, r.status());
                    assertEquals(1, r.attempt());
                    assertEquals(t0.plusSeconds(1), r.fireAt());
                    assertNotNull(r.lastError());

                    // Not due yet at t0+0.5s.
                    clock.advance(Duration.ofMillis(500));
                    assertEquals(0, worker.drainOnce());

                    // Due at t0+1s → attempt 2 → failure → rescheduled at +2s from then.
                    clock.advance(Duration.ofMillis(500));
                    assertEquals(1, worker.drainOnce());
                    r = storage.find(id).orElseThrow();
                    assertEquals(ScheduledCommandStatus.PENDING, r.status());
                    assertEquals(2, r.attempt());

                    // Advance enough for the third attempt → success.
                    clock.advance(Duration.ofMinutes(1));
                    assertEquals(1, worker.drainOnce());
                    r = storage.find(id).orElseThrow();
                    assertEquals(ScheduledCommandStatus.DISPATCHED, r.status());
                    assertEquals(3, r.attempt());
                    assertEquals(3, seen.get());
                }
            } finally {
                bus.unregister(handler);
            }
        }
    }

    @Test
    void exhausted_maxAttempts_marksFailed() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            CommandBus bus     = runtime.commands();
            var        handler =
                    new AbstractNoReturnCommandHandler<Ping>() {
                                           @Override
                                           public boolean isSagaEnabled() {
                                               return true;
                                           }

                                           @Override
                                           protected void handle(Ping command) {
                                               throw new RuntimeException("always fails");
                                           }
                                       };
            bus.register(handler);
            try {
                Instant                         t0      = Instant.parse("2030-01-01T00:00:00Z");
                TestClock                       clock   = new TestClock(t0);
                InMemoryScheduledCommandStorage storage = new InMemoryScheduledCommandStorage();
                ScheduledCommandConfig          cfg     =
                        ScheduledCommandConfig.builder(storage)
                                .clock(clock)
                                .pollInterval(Duration.ofMillis(50))
                                .maxAttempts(2)
                                .backoffBase(Duration.ofMillis(1))
                                .backoffMax(Duration.ofMillis(10))
                                .build();
                try (ScheduledCommandWorker worker = new ScheduledCommandWorker(cfg, bus)) {
                    ScheduledCommandId id = ScheduledCommandId.random();
                    storage.schedule(
                                     ScheduledCommandRecord.create(
                                                                   id, Command.<Ping>builder().body(new Ping("p")).build(), t0, t0));

                    // Attempt 1.
                    assertEquals(1, worker.drainOnce());
                    var r = storage.find(id).orElseThrow();
                    assertEquals(ScheduledCommandStatus.PENDING, r.status());
                    assertEquals(1, r.attempt());

                    // Advance and exhaust.
                    clock.advance(Duration.ofSeconds(1));
                    assertEquals(1, worker.drainOnce());
                    r = storage.find(id).orElseThrow();
                    assertEquals(ScheduledCommandStatus.FAILED_TERMINAL, r.status());
                    assertEquals(2, r.attempt());
                    assertNotNull(r.lastError());
                }
            } finally {
                bus.unregister(handler);
            }
        }
    }

    @Test
    void duplicate_scheduleId_raises() {
        InMemoryScheduledCommandStorage storage = new InMemoryScheduledCommandStorage();
        Instant                         t0      = Instant.parse("2030-01-01T00:00:00Z");
        ScheduledCommandId              id      = ScheduledCommandId.random();
        Command<Ping>                   cmd     = Command.<Ping>builder().body(new Ping("p")).build();
        storage.schedule(ScheduledCommandRecord.create(id, cmd, t0, t0));
        assertThrows(
                     ScheduledCommandDuplicateException.class,
                     () -> storage.schedule(ScheduledCommandRecord.create(id, cmd, t0, t0)));
    }

    @Test
    void claimDue_ordersBy_fireAtThenId() {
        InMemoryScheduledCommandStorage storage = new InMemoryScheduledCommandStorage();
        Instant                         t0      = Instant.parse("2030-01-01T00:00:00Z");
        Command<Ping>                   cmd     = Command.<Ping>builder().body(new Ping("p")).build();

        ScheduledCommandId a = ScheduledCommandId.random();
        ScheduledCommandId b = ScheduledCommandId.random();
        ScheduledCommandId c = ScheduledCommandId.random();

        storage.schedule(ScheduledCommandRecord.create(a, cmd, t0.plusSeconds(2), t0));
        storage.schedule(ScheduledCommandRecord.create(b, cmd, t0, t0));
        storage.schedule(ScheduledCommandRecord.create(c, cmd, t0.plusSeconds(1), t0));

        List<ScheduledCommandRecord> due = storage.claimDue(10, t0.plusSeconds(10));
        assertEquals(3, due.size());
        assertEquals(b, due.get(0).id());
        assertEquals(c, due.get(1).id());
        assertEquals(a, due.get(2).id());
    }
}
