package net.nexus_flow.core.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * Lifecycle plumbing shared by the framework's daemon-driven workers ({@code OutboxWorker}, {@code
 * ScheduledCommandWorker}, and any future polling worker that owns a single background platform
 * thread).
 *
 * <h2>What this base provides</h2>
 *
 * <ul>
 * <li>A {@code daemon=true} platform thread named per the subclass that runs {@link #runLoop()}.
 * <li>An idempotent {@link #start()} gate — only the first call schedules the thread.
 * <li>A worker-lifetime {@link CancellationToken} ({@link #workerToken}) that subclasses embed in
 * every {@link ExecutionContext} they build; {@link #cancelInterruptJoin} cancels it before
 * interrupting the daemon so handlers polling {@code
 *       FlowScope.current().get().throwIfCancelledOrExpired()} observe cooperative cancellation
 * during shutdown.
 * <li>A {@link #tryBeginShutdown()} guard so subclasses get the {@code running} CAS for free —
 * the {@code if (!cas(true, false)) return} idiom at the top of every {@code shutdown()}
 * cannot be forgotten.
 * <li>A {@link #cancelInterruptJoin(Duration)} helper that bundles the cancel → interrupt →
 * {@code Thread.join(grace)} dance the subclass would otherwise inline.
 * <li>A {@link #close()} alias for {@code shutdown()} so every worker is uniformly usable in a
 * try-with-resources.
 * </ul>
 *
 * <h2>Why a shared base, not three workers inlining the same plumbing</h2>
 *
 * <p>The original sin captured in the framework's audit log was a worker whose dispatch built a
 * <em>fresh per-record</em> {@link CancellationToken} — orphan, never cancelled on shutdown — so
 * {@code shutdown()} interrupted the daemon thread but could not cooperatively stop the in-flight
 * handler. The fix was to plumb a worker-lifetime token through every {@link ExecutionContext} the
 * worker built. Holding the token in this base class makes that fix structural: a new worker
 * subclassing {@link AbstractDaemonWorker} cannot accidentally reintroduce the orphan-token pattern
 * because the token is owned here, not synthesized at dispatch time.
 *
 * <h2>What this base does NOT provide</h2>
 *
 * Each subclass owns its own {@code runLoop()} body, {@code shutdown(...)} overloads (OutboxWorker
 * has a {@code ShutdownMode} parameter; ScheduledCommandWorker does not), batch draining, payload
 * codec resolution, and error-classification logging. The shapes diverge by design — the base
 * captures only the parts that MUST be uniform across workers for the shutdown cooperation contract
 * to hold.
 *
 * <h2>Visibility</h2>
 *
 * Public for cross-package consumption (both workers live in their own packages). NOT an
 * adapter-facing SPI — external integrators do not subclass this; they implement {@code
 * OutboxStorage} / {@code ScheduledCommandStorage} and let the framework's workers drive them.
 */
public abstract class AbstractDaemonWorker implements AutoCloseable {

    /**
     * One-shot flag flipped by {@link #tryBeginShutdown()}; {@link #isRunning()} returns its negated
     * value. Private so subclasses cannot bypass the CAS guard accidentally.
     */
    private final AtomicBoolean running = new AtomicBoolean(true);

    /** One-shot start gate. {@link #start()} is a no-op after the first call. */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * Worker-lifetime cancellation token. Every {@link ExecutionContext} the subclass builds for a
     * dispatched record MUST embed this token; {@link #cancelInterruptJoin} cancels it BEFORE
     * interrupting the daemon thread so handlers that poll {@code
     * FlowScope.current().get().throwIfCancelledOrExpired()} observe cooperative cancellation through
     * their context. Handlers blocked on interruptible code wake via the subsequent {@link
     * Thread#interrupt()}. Handlers that poll neither cannot be force-stopped — {@link
     * Thread#join(long)} bounds the wait at the subclass-supplied grace and the shutdown returns
     * anyway.
     */
    protected final CancellationToken workerToken = CancellationToken.create();

    /** Configured at construction; the {@link Thread} is built lazily in {@link #start()}. */
    private final String threadName;

    /**
     * The daemon platform thread that runs {@link #runLoop()}. Lazily constructed on the first call
     * to {@link #start()} so the {@code this::runLoop} method reference is not captured during the
     * super-constructor (avoids the {@code this-escape} warning and the latent ordering hazard it
     * represents — subclass fields are guaranteed to be initialised by the time the lambda captures
     * {@code this}). {@code null} until {@code start()} fires.
     */
    private volatile @Nullable Thread thread;

    /**
     * Construct a daemon worker bound to a named platform thread.
     *
     * @param threadName the name given to the platform thread for diagnostics / thread dumps; must
     *                   not be {@code null} or blank
     * @throws NullPointerException     if {@code threadName} is {@code null}
     * @throws IllegalArgumentException if {@code threadName} is blank
     */
    protected AbstractDaemonWorker(String threadName) {
        Objects.requireNonNull(threadName, "threadName");
        if (threadName.isBlank()) {
            throw new IllegalArgumentException("threadName must not be blank");
        }
        this.threadName = threadName;
    }

    /**
     * Starts the background daemon thread. Safe to call multiple times — only the first call actually
     * constructs the thread and schedules it; subsequent calls are no-ops.
     */
    public final void start() {
        if (started.compareAndSet(false, true)) {
            Thread t = Thread.ofPlatform().daemon(true).name(threadName).unstarted(this::runLoop);
            this.thread = t;
            t.start();
        }
    }

    /**
     * Returns {@code true} while the worker has not begun shutting down (i.e. {@link
     * #tryBeginShutdown()} has not flipped the flag yet). Atomic read; safe for concurrent access.
     * Subclass {@link #runLoop()} bodies should use this to terminate the poll loop.
     */
    public final boolean isRunning() {
        return running.get();
    }

    /**
     * Idempotency guard for subclass {@code shutdown(...)} implementations. Returns {@code true} on
     * the first call (the one that should perform the actual shutdown work), {@code false} on every
     * subsequent call. The subclass MUST call this at the top of its {@code shutdown()}:
     *
     * <pre>{@code
     * @Override
     * public void shutdown() {
     * if (!tryBeginShutdown()) return;
     * // ... drain / hook work ...
     * cancelInterruptJoin(config.workerShutdownGrace());
     * }
     * }</pre>
     *
     * @return {@code true} if this is the first call (proceed with shutdown), {@code false} if
     *         shutdown has already begun (no-op)
     */
    protected final boolean tryBeginShutdown() {
        return running.compareAndSet(true, false);
    }

    /**
     * Cancel the worker-lifetime token, interrupt the daemon thread, then block until the thread
     * exits (up to {@code grace}). Standard tail of every worker's {@code shutdown(...)}; pulled out
     * of the subclasses so the cancel → interrupt → join sequence (whose order is load-bearing per
     * the §11.3 audit) cannot drift.
     *
     * @param grace maximum time to wait in {@link Thread#join(long)} after the interrupt; must be
     *              positive; if the thread does not exit in time the caller's interrupt status is restored and
     *              this method returns anyway
     * @throws NullPointerException if {@code grace} is {@code null}
     */
    protected final void cancelInterruptJoin(Duration grace) {
        Objects.requireNonNull(grace, "grace");
        workerToken.cancel();
        Thread t = this.thread;
        if (t == null) {
            // shutdown() called before start(): no daemon was ever scheduled, nothing to interrupt
            // or join. The cancelled workerToken still propagates to any future ExecutionContext
            // the subclass might build, which is the right defensive behavior.
            return;
        }
        t.interrupt();
        try {
            t.join(grace.toMillis());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Subclass body of the polling loop. Runs on the daemon thread. Should terminate when {@link
     * #isRunning()} returns {@code false}.
     */
    protected abstract void runLoop();

    /**
     * Subclass shutdown protocol. Implementations MUST be idempotent (use {@link #tryBeginShutdown()}
     * as the first line) and end by calling {@link #cancelInterruptJoin(Duration)} with the
     * subclass-supplied grace.
     */
    public abstract void shutdown();

    /** Delegates to {@link #shutdown()}. Enables uniform try-with-resources use across workers. */
    @Override
    public final void close() {
        shutdown();
    }
}
