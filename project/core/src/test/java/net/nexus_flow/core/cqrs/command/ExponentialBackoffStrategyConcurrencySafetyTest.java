package net.nexus_flow.core.cqrs.command;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Pins the mutable-but-thread-safe contract of {@link ExponentialBackoffStrategy} and the
 * configuration plumbing through {@link BackoffSettings}.
 *
 * <p>The strategy is intentionally mutable + {@code synchronized} (NOT immutable + {@code
 * AtomicReference}): the state is just two correlated primitives ({@code long waitMillis} + {@code
 * boolean inBackoffState}), {@link BackoffStrategy#reset()} needs both fields to transition
 * together atomically, and snapshot semantics are not valuable here. The immutable +
 * AtomicReference + CAS pattern stays as the documented recipe for COMPLEX shared state with
 * multiple correlated fields; for two primitives it is overkill.
 *
 * <p>Invariants pinned by this suite:
 *
 * <ol>
 * <li>Settings validation rejects illegal inputs.
 * <li>Initial state: returns {@code baseWaitMillis}, NOT in backoff.
 * <li>{@link BackoffStrategy#nextWaitAndAdvance()} returns increasing waits + flips the
 * in-backoff flag on the SECOND advance (matching legacy semantics).
 * <li>Advance caps at {@code maxWaitMillis}.
 * <li>{@link BackoffStrategy#reset()} atomically returns to base.
 * <li>{@link BackoffStrategy#cancellationPoll()} is configuration-driven, invariant across
 * advances/resets.
 * <li>Concurrent {@code nextWaitAndAdvance} callers converge on the cap without exceptions and
 * without leaving the state inconsistent.
 * </ol>
 */
class ExponentialBackoffStrategyConcurrencySafetyTest {

    @Test
    void settings_rejectIllegalBaseAndMaxAndPoll() {
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new BackoffSettings(0L, 1000L, Duration.ofMillis(25L)));
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new BackoffSettings(10L, 5L, Duration.ofMillis(25L)),
                     "maxWaitMillis < baseWaitMillis must be rejected");
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new BackoffSettings(1L, 1000L, Duration.ZERO),
                     "zero cancellationPoll must be rejected");
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new BackoffSettings(1L, 1000L, Duration.ofMillis(-1L)),
                     "negative cancellationPoll must be rejected");
    }

    @Test
    void initialState_returnsBase_notInBackoff() {
        BackoffStrategy s =
                new ExponentialBackoffStrategy(new BackoffSettings(1L, 1000L, Duration.ofMillis(25L)));
        assertFalse(s.isInBackoffState(), "fresh strategy must not be in backoff");
        Duration first = s.nextWaitAndAdvance();
        assertEquals(Duration.ofMillis(1L), first, "first call must return baseWaitMillis (1 ms)");
    }

    @Test
    void inBackoffStateFlipsOnSecondAdvance_matchingLegacyContract() {
        BackoffStrategy s =
                new ExponentialBackoffStrategy(new BackoffSettings(1L, 1_000_000L, Duration.ofMillis(25L)));
        assertFalse(s.isInBackoffState(), "initial: not in backoff");

        s.nextWaitAndAdvance(); // 1 → 2; flag stays false (current was 1, not > 1).
        assertFalse(
                    s.isInBackoffState(), "after first advance (waitMillis 1 → 2) the flag MUST stay false");

        s.nextWaitAndAdvance(); // 2 → 4; flag flips (current was 2 > 1).
        assertTrue(
                   s.isInBackoffState(), "after second advance (waitMillis 2 → 4) the flag MUST flip to true");
    }

    @Test
    void advance_progressesAndCapsAtMaxWaitMillis() {
        BackoffStrategy s = new ExponentialBackoffStrategy(8L);
        // 1, 2, 4, 8, 8, 8, …
        assertEquals(Duration.ofMillis(1L), s.nextWaitAndAdvance());
        assertEquals(Duration.ofMillis(2L), s.nextWaitAndAdvance());
        assertEquals(Duration.ofMillis(4L), s.nextWaitAndAdvance());
        assertEquals(Duration.ofMillis(8L), s.nextWaitAndAdvance());
        assertEquals(Duration.ofMillis(8L), s.nextWaitAndAdvance(), "MUST cap at maxWaitMillis");
        assertEquals(Duration.ofMillis(8L), s.nextWaitAndAdvance(), "MUST stay at cap");
    }

    @Test
    void reset_restoresBaseAndClearsFlag() {
        BackoffStrategy s = new ExponentialBackoffStrategy(1_000L);
        s.nextWaitAndAdvance();
        s.nextWaitAndAdvance();
        s.nextWaitAndAdvance();
        assertTrue(s.isInBackoffState(), "sanity: in backoff before reset");

        s.reset();
        assertFalse(s.isInBackoffState(), "reset MUST clear the in-backoff flag");
        assertEquals(
                     Duration.ofMillis(1L),
                     s.nextWaitAndAdvance(),
                     "reset MUST return waitMillis to baseWaitMillis (1 ms)");
    }

    @Test
    void cancellationPoll_isConfigurable_andInvariantAcrossTransitions() {
        Duration        customPoll = Duration.ofMillis(73L);
        BackoffStrategy s          = new ExponentialBackoffStrategy(new BackoffSettings(1L, 1000L, customPoll));
        assertEquals(customPoll, s.cancellationPoll(), "configured poll must surface");
        s.nextWaitAndAdvance();
        s.nextWaitAndAdvance();
        assertEquals(customPoll, s.cancellationPoll(), "advances MUST NOT change cancellationPoll");
        s.reset();
        assertEquals(customPoll, s.cancellationPoll(), "reset MUST NOT change cancellationPoll");
    }

    @Test
    void customBaseAndMax_arePlumbedThroughSettings() {
        BackoffSettings settings = new BackoffSettings(100L, 800L, Duration.ofMillis(50L));
        BackoffStrategy s        = new ExponentialBackoffStrategy(settings);
        assertEquals(Duration.ofMillis(50L), s.cancellationPoll());
        assertEquals(Duration.ofMillis(100L), s.nextWaitAndAdvance(), "first wait MUST equal base");
        assertEquals(Duration.ofMillis(200L), s.nextWaitAndAdvance());
        assertEquals(Duration.ofMillis(400L), s.nextWaitAndAdvance());
        assertEquals(Duration.ofMillis(800L), s.nextWaitAndAdvance(), "MUST cap at max");
        assertEquals(Duration.ofMillis(800L), s.nextWaitAndAdvance(), "MUST stay at cap");
    }

    /**
     * Concurrency smoke test: many threads hammer {@code nextWaitAndAdvance} simultaneously. With
     * {@code synchronized} mutual exclusion the strategy MUST converge to the cap (every call
     * progresses by one step, so 50 advances with cap {@code 2^20} reaches the cap many times over).
     * The test verifies no exceptions, terminal state at cap, in-backoff flag set.
     *
     * <p>Unlike the AtomicReference + immutable variant, {@code synchronized} guarantees mutual
     * exclusion so "lost updates" are impossible by construction — the goal here is to prove the lock
     * plumbing is correct and the strategy stays consistent under contention, not to disprove a race
     * that the language already prevents.
     */
    @Test
    void concurrentNextWaitAndAdvance_convergesToCapWithoutErrors() throws Exception {
        long            maxMillis = 1L << 20; // 1 048 576 ms cap; reached after ~21 advances from base 1.
        BackoffStrategy s         =
                new ExponentialBackoffStrategy(new BackoffSettings(1L, maxMillis, Duration.ofMillis(25L)));

        int threadCount    = 16;
        int callsPerThread = 50;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threadCount);
        for (int t = 0; t < threadCount; t++) {
            Thread runner =
                    new Thread(
                            () -> {
                                try {
                                    start.await();
                                    for (int i = 0; i < callsPerThread; i++) {
                                        s.nextWaitAndAdvance();
                                    }
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    done.countDown();
                                }
                            },
                            "backoff-advance-" + t);
            runner.setDaemon(true);
            runner.start();
        }
        start.countDown();
        assertTrue(
                   done.await(10L, TimeUnit.SECONDS),
                   "advance threads must complete within 10s — if this times out, synchronized is hanging");

        // Final state: cap was hit and stays there.
        assertEquals(
                     Duration.ofMillis(maxMillis),
                     s.nextWaitAndAdvance(),
                     "after many concurrent advances and a small cap, the next call MUST return the cap");
        assertTrue(s.isInBackoffState(), "many advances MUST have flipped the in-backoff flag to true");
    }
}
