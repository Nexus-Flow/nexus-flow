package net.nexus_flow.core.ddd;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.nexus_flow.core.cqrs.event.DomainEventContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Concurrency-stress tests for {@link Aggregate#recordEvent(DomainEvent)} and {@link
 * Aggregate#replay(DomainEvent)}.
 *
 * <p>The class lives in the {@code ddd} package so the package-private kill-switch ({@code
 * Aggregate.useAggregateLocalEvents}) can be reset between tests.
 *
 * <p>Goals:
 *
 * <ul>
 * <li>Concurrent {@code recordEvent} on the same aggregate must produce strictly monotonic,
 * gap-free sequence numbers (the {@code lifecycleMonitor} contract).
 * <li>Concurrent {@code recordEvent} on different aggregates must not interfere — sequences are
 * per-instance.
 * <li>{@code replay} after concurrent {@code recordEvent} must continue assigning sequences
 * higher than the highest replayed sequence (the {@code nextSequenceNumber} catch-up).
 * </ul>
 */
class AggregateConcurrentRecordStressTest {

    static final class Step extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Step(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Counter extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        final AtomicLong applied = new AtomicLong();

        @Override
        protected void apply(DomainEvent event) {
            applied.incrementAndGet();
        }

        void bump() {
            recordEvent(new Step("c-1"));
        }

        long bumpAndReturnSeq() {
            Step ev = new Step("c-1");
            recordEvent(ev);
            return ev.getSequenceNumber();
        }
    }

    @BeforeEach
    void clearContext() {
        DomainEventContext.current().clearEvents();
    }

    @AfterEach
    void clearContextAfter() {
        DomainEventContext.current().clearEvents();
    }

    @Test
    @DisplayName(
        "Concurrent recordEvent on a single aggregate produces strictly monotonic, gap-free"
                + " sequences")
    void recordEvent_underContention_monotonicAndGapFree() throws Exception {
        Counter agg             = new Counter();
        int     threads         = 16;
        int     eventsPerThread = 200;
        int     total           = threads * eventsPerThread;

        Set<Long>      seqs  = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            Thread.ofVirtual()
                    .start(
                           () -> {
                               try {
                                   start.await();
                                   for (int i = 0; i < eventsPerThread; i++) {
                                       long seq = agg.bumpAndReturnSeq();
                                       seqs.add(seq);
                                   }
                               } catch (InterruptedException e) {
                                   Thread.currentThread().interrupt();
                               } finally {
                                   done.countDown();
                               }
                           });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));

        assertEquals(total, seqs.size(), "every recordEvent must produce a unique sequence number");
        // gap-free: 0 .. total-1
        for (long s = 0; s < total; s++) {
            assertTrue(seqs.contains(s), "missing sequence " + s);
        }
        assertEquals(total, agg.applied.get(), "apply() invoked exactly once per recorded event");
        List<DomainEvent> uncommitted = agg.drainEvents();
        assertEquals(total, uncommitted.size(), "uncommitted buffer must reflect every recorded event");
    }

    @Test
    @DisplayName("Per-instance sequences: independent aggregates have independent counters")
    void recordEvent_perInstanceSequence_isIndependent() throws Exception {
        int           aggregates = 24;
        int           eventsPer  = 50;
        List<Counter> all        = new ArrayList<>();
        for (int i = 0; i < aggregates; i++) {
            all.add(new Counter());
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(aggregates);

        for (Counter agg : all) {
            Thread.ofVirtual()
                    .start(
                           () -> {
                               try {
                                   start.await();
                                   for (int i = 0; i < eventsPer; i++) {
                                       agg.bump();
                                   }
                               } catch (InterruptedException e) {
                                   Thread.currentThread().interrupt();
                               } finally {
                                   done.countDown();
                               }
                           });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));

        for (Counter agg : all) {
            List<DomainEvent> drained = agg.drainEvents();
            assertEquals(eventsPer, drained.size());
            Set<Long> seqs = new HashSet<>();
            for (DomainEvent e : drained) {
                seqs.add(((AbstractDomainEvent) e).getSequenceNumber());
            }
            assertEquals(eventsPer, seqs.size());
            // Each aggregate sees 0..eventsPer-1 exclusively (no cross-talk).
            for (long s = 0; s < eventsPer; s++) {
                assertTrue(seqs.contains(s), "aggregate missing sequence " + s);
            }
        }
    }

    @Test
    @DisplayName("Replay after recordEvent advances nextSequenceNumber past the replayed stream")
    void replayThenRecord_catchesUpSequenceCounter() {
        Counter agg = new Counter();

        // Replay simulated historical events with sparse sequences (gap up to 99).
        Step historical = new Step("c-1");
        historical.assignSequenceNumber(99L);
        agg.replay(historical);

        // The next freshly recorded event must take sequence 100 (not 0).
        long firstFresh = agg.bumpAndReturnSeq();
        assertEquals(100L, firstFresh);
        long secondFresh = agg.bumpAndReturnSeq();
        assertEquals(101L, secondFresh);
    }

    @Test
    @DisplayName("recordEvent on a pre-stamped event throws and leaves the aggregate untouched")
    void recordEvent_rejectsPreStampedEvent() {
        Counter agg        = new Counter();
        Step    preStamped = new Step("c-1");
        preStamped.assignSequenceNumber(42L);

        assertThrows(IllegalStateException.class, () -> agg.recordEvent(preStamped));
        assertEquals(0, agg.applied.get(), "apply() must not have been invoked");
        assertTrue(agg.drainEvents().isEmpty(), "uncommitted buffer must remain empty");
    }

    @Test
    @DisplayName("recordEvent rejects null with a clear NPE")
    void recordEvent_rejectsNull() {
        Counter agg = new Counter();
        assertThrows(NullPointerException.class, () -> agg.recordEvent((DomainEvent) null));
        assertThrows(NullPointerException.class, () -> agg.recordEvent((List<DomainEvent>) null));
    }

    @Test
    @DisplayName("apply() observes the sequence number stamped before invocation")
    void apply_seesAssignedSequence() {
        class Asserting extends Aggregate {
            @Serial
            private static final long  serialVersionUID  = 1L;
            final transient List<Long> observedSequences = new ArrayList<>();

            @Override
            protected void apply(DomainEvent event) {
                observedSequences.add(((AbstractDomainEvent) event).getSequenceNumber());
            }

            void emit() {
                recordEvent(new Step("a-1"));
            }
        }

        Asserting agg = new Asserting();
        for (int i = 0; i < 5; i++) {
            agg.emit();
        }
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), agg.observedSequences);
        // Verify with Awaitility that the drain reflects steady state quickly
        // (deterministic in this synchronous context — sanity check on the
        // dependency wiring).
        await()
                .atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> assertEquals(5, agg.drainEvents().size()));
    }
}
