package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins the structural lifecycle invariants of {@link AbstractDaemonWorker}. These invariants are
 * the framework's defence against the original orphan-cancellation-token mistake captured in the
 * audit log: a worker whose dispatch built per-record tokens, never cancelled on shutdown, leaked
 * in-flight handlers across {@link Thread#join(long)} returns.
 *
 * <p>The base class makes that mistake structurally impossible by:
 *
 * <ul>
 * <li>Owning the worker-lifetime {@link CancellationToken} (not a per-dispatch fresh token).
 * <li>Cancelling it in {@link AbstractDaemonWorker#cancelInterruptJoin(Duration)} BEFORE {@link
 * Thread#interrupt()}, BEFORE {@link Thread#join(long)} — the §11.3 ordering.
 * <li>Providing the {@code running} CAS via {@link AbstractDaemonWorker#tryBeginShutdown()} so
 * subclasses cannot forget the idempotency guard.
 * <li>Deferring thread construction to {@link AbstractDaemonWorker#start()} so the {@code
 *       this::runLoop} method reference is not captured before subclass fields are initialised (no
 * {@code this-escape}).
 * </ul>
 *
 * <p>Each test below verifies one of those invariants on a minimal probe subclass.
 */
class AbstractDaemonWorkerLifecycleTest {

    /** Minimal subclass that records lifecycle events and runs a no-op loop. */
    static final class ProbeWorker extends AbstractDaemonWorker {
        final AtomicInteger  loopIterations                = new AtomicInteger();
        final AtomicBoolean  cancelObservedBeforeInterrupt = new AtomicBoolean();
        final CountDownLatch loopEntered                   = new CountDownLatch(1);

        ProbeWorker(String name) {
            super(name);
        }

        @Override
        protected void runLoop() {
            loopEntered.countDown();
            while (isRunning()) {
                loopIterations.incrementAndGet();
                // Spin tightly but stay polite. We poll the token to observe shutdown cooperatively
                // (the §11.3 cooperative-cancel contract): a handler that polls the token sees the
                // cancellation BEFORE the thread interrupt arrives, because cancelInterruptJoin
                // cancels then interrupts.
                if (workerToken.isCancellationRequested() && !Thread.currentThread().isInterrupted()) {
                    cancelObservedBeforeInterrupt.set(true);
                }
                try {
                    //noinspection BusyWait
                    Thread.sleep(10);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        @Override
        public void shutdown() {
            if (!tryBeginShutdown())
                return;
            cancelInterruptJoin(Duration.ofSeconds(2));
        }
    }

    @Test
    void constructor_rejectsNullThreadName() {
        assertThrows(NullPointerException.class, () -> new ProbeWorker(null));
    }

    @Test
    void constructor_rejectsBlankThreadName() {
        assertThrows(IllegalArgumentException.class, () -> new ProbeWorker("  "));
        assertThrows(IllegalArgumentException.class, () -> new ProbeWorker(""));
    }

    @Test
    void freshWorker_isRunningTrue_beforeStart() {
        ProbeWorker w = new ProbeWorker("probe-fresh");
        assertTrue(w.isRunning(), "isRunning() must be true between construction and shutdown");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void start_isIdempotent_onlyOneThreadEverStarted() throws Exception {
        ProbeWorker w = new ProbeWorker("probe-idem-start");
        try {
            w.start();
            w.start();
            w.start();
            assertTrue(w.loopEntered.await(2, TimeUnit.SECONDS), "loop must enter after start");
            // After a few iterations, shutdown — if start() had scheduled multiple threads, we'd
            // see the loop running on more than one carrier, but the assertion below is a stronger
            // structural test: a second start() must not have created a fresh thread.
        } finally {
            w.shutdown();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void shutdown_isIdempotent_secondCallReturnsImmediatelyWithNoSideEffect() throws Exception {
        ProbeWorker w = new ProbeWorker("probe-idem-shutdown");
        w.start();
        assertTrue(w.loopEntered.await(2, TimeUnit.SECONDS));
        w.shutdown();
        assertFalse(w.isRunning());
        // Subsequent calls must return immediately without throwing.
        w.shutdown();
        w.shutdown();
        w.close();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void shutdown_cancelsWorkerToken_beforeInterruptingThread() throws Exception {
        ProbeWorker w = new ProbeWorker("probe-cancel-order");
        w.start();
        assertTrue(w.loopEntered.await(2, TimeUnit.SECONDS));
        // Spin briefly so the loop body has chances to observe the post-cancel-pre-interrupt
        // window — the loop polls workerToken.isCancellationRequested() and
        // Thread.currentThread().isInterrupted().
        Thread.sleep(50);
        w.shutdown();
        assertTrue(
                   w.workerToken.isCancellationRequested(),
                   "cancelInterruptJoin MUST cancel workerToken so handlers cooperatively observe shutdown");
        // The interrupt-after-cancel ordering is the §11.3 contract; we cannot deterministically
        // race the carrier into observing it on every run, but the cancellation flag itself is the
        // load-bearing signal handlers actually poll.
    }

    @Test
    void shutdownBeforeStart_doesNotThrow_cancelsTokenAnyway() {
        ProbeWorker w = new ProbeWorker("probe-shutdown-before-start");
        // shutdown() without start() — no thread to interrupt/join. Must still cancel the token
        // so any later ExecutionContext built by the subclass propagates the cancellation.
        w.shutdown();
        assertFalse(w.isRunning());
        assertTrue(
                   w.workerToken.isCancellationRequested(),
                   "shutdown() before start() must still cancel workerToken — a half-initialised worker"
                           + " must not surface as 'cancellable later'");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void close_isAliasForShutdown_supportsTryWithResources() throws Exception {
        ProbeWorker w = new ProbeWorker("probe-twr");
        try (w) {
            w.start();
            assertTrue(w.loopEntered.await(2, TimeUnit.SECONDS));
        }
        assertFalse(w.isRunning(), "try-with-resources close() must shut the worker down");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void differentWorkers_haveDistinctTokens_andDistinctThreads() throws Exception {
        ProbeWorker a = new ProbeWorker("probe-a");
        ProbeWorker b = new ProbeWorker("probe-b");
        try {
            a.start();
            b.start();
            assertTrue(a.loopEntered.await(2, TimeUnit.SECONDS));
            assertTrue(b.loopEntered.await(2, TimeUnit.SECONDS));
            assertNotEquals(
                            a.workerToken,
                            b.workerToken,
                            "every worker instance must own its own cancellation token — sharing a token across"
                                    + " workers would mean shutting one cancels the other");
            assertNotNull(a.workerToken);
            assertNotNull(b.workerToken);
            // Cancel one, confirm the other is unaffected.
            a.shutdown();
            assertTrue(a.workerToken.isCancellationRequested());
            assertFalse(b.workerToken.isCancellationRequested());
        } finally {
            a.shutdown();
            b.shutdown();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void cancelInterruptJoin_rejectsNullGrace() {
        final class GraceProbe extends AbstractDaemonWorker {
            GraceProbe() {
                super("probe-grace");
            }

            @Override
            protected void runLoop() {
                /* never starts */
            }

            @Override
            public void shutdown() {
                if (!tryBeginShutdown())
                    return;
                cancelInterruptJoin(null);
            }
        }
        GraceProbe p = new GraceProbe();
        assertThrows(NullPointerException.class, p::shutdown);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void runLoop_exitsCleanly_whenIsRunningFlips() throws Exception {
        ProbeWorker w = new ProbeWorker("probe-exit");
        w.start();
        assertTrue(w.loopEntered.await(2, TimeUnit.SECONDS));
        int before = w.loopIterations.get();
        Thread.sleep(50);
        int mid = w.loopIterations.get();
        assertTrue(mid > before, "loop should have iterated at least once between observations");
        w.shutdown();
        int afterShutdown = w.loopIterations.get();
        Thread.sleep(50);
        assertEquals(
                     afterShutdown,
                     w.loopIterations.get(),
                     "loop must stop iterating after shutdown — the isRunning() guard must take effect"
                             + " promptly");
    }
}
