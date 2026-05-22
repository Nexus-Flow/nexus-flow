package net.nexus_flow.core.eventsourcing;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Drives a {@link Projection} against an {@link EventStore}.
 *
 * <p>Two operating modes:
 *
 * <ul>
 * <li>{@link #catchUp()} — synchronous, reads from {@code checkpoint+1} to the head in batches
 * and applies envelopes inline on the calling thread. Returns when {@link
 * EventStore#readAll(long, long)} yields an empty slice.
 * <li>{@link #start()} — returns a {@link CompletableFuture} that completes when {@link #close()}
 * is called. The runner spawns a single daemon thread that polls the store on {@link
 * #pollInterval()} intervals.
 * </ul>
 *
 * <p>The checkpoint is saved AFTER every successful {@link Projection#apply(EventEnvelope)} call;
 * on crash the next start() picks up from the last persisted position. The {@code Projection}
 * contract requires {@code apply} to be idempotent for the rare case where the checkpoint write
 * fails after a successful apply.
 */
public final class ProjectionRunner implements AutoCloseable {

    private static final Logger LOG = System.getLogger(ProjectionRunner.class.getName());

    /**
     * Maximum envelopes read per {@link EventStore#readAll(long, long)} call. Mirrored from {@link
     * ProjectionRunnerConfig#DEFAULT_BATCH_SIZE} for backwards-compatible references.
     */
    public static final long DEFAULT_BATCH_SIZE = ProjectionRunnerConfig.DEFAULT_BATCH_SIZE;

    /**
     * Default poll interval when the head is reached. Mirrored from {@link
     * ProjectionRunnerConfig#DEFAULT_POLL_INTERVAL}.
     */
    public static final Duration DEFAULT_POLL_INTERVAL = ProjectionRunnerConfig.DEFAULT_POLL_INTERVAL;

    private final EventStore                store;
    private final ProjectionCheckpointStore checkpoints;
    private final Projection                projection;
    private final ProjectionRunnerConfig    config;

    private final AtomicBoolean                                      running          = new AtomicBoolean(false);
    private final AtomicReference<@Nullable Thread>                  pollerThread     = new AtomicReference<>();
    private final AtomicReference<@Nullable CompletableFuture<Void>> completionFuture =
            new AtomicReference<>();

    /**
     * Create a projection runner with {@link ProjectionRunnerConfig#DEFAULTS}.
     *
     * @param store       the event store to read from; non-null
     * @param checkpoints checkpoint store for persisting progress; non-null
     * @param projection  the projection to apply envelopes to; non-null
     */
    public ProjectionRunner(
            EventStore store, ProjectionCheckpointStore checkpoints, Projection projection) {
        this(store, checkpoints, projection, ProjectionRunnerConfig.DEFAULTS);
    }

    /**
     * Preferred constructor — bundles tuning in {@link ProjectionRunnerConfig}.
     *
     * @param store       the event store to read from; non-null
     * @param checkpoints checkpoint store for persisting progress; non-null
     * @param projection  the projection to apply envelopes to; non-null
     * @param config      tuning configuration; non-null
     */
    public ProjectionRunner(
            EventStore store,
            ProjectionCheckpointStore checkpoints,
            Projection projection,
            ProjectionRunnerConfig config) {
        this.store       = Objects.requireNonNull(store, "store");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.projection  = Objects.requireNonNull(projection, "projection");
        this.config      = Objects.requireNonNull(config, "config");
    }

    /**
     * Backwards-compatible convenience constructor with explicit {@code batchSize} and {@code
     * pollInterval}; defaults the shutdown grace.
     *
     * @param store        the event store to read from; non-null
     * @param checkpoints  checkpoint store for persisting progress; non-null
     * @param projection   the projection to apply envelopes to; non-null
     * @param batchSize    maximum envelopes per {@link #catchUp()} round; must be {@code >= 1}
     * @param pollInterval how long to sleep after reaching the head before polling again; must be
     *                     {@code > 0}
     */
    public ProjectionRunner(
            EventStore store,
            ProjectionCheckpointStore checkpoints,
            Projection projection,
            long batchSize,
            Duration pollInterval) {
        this(
             store,
             checkpoints,
             projection,
             ProjectionRunnerConfig.builder().batchSize(batchSize).pollInterval(pollInterval).build());
    }

    /**
     * Return the poll interval used when the envelope stream reaches the head.
     *
     * @return idle polling delay between catch-up cycles
     */
    public Duration pollInterval() {
        return config.pollInterval();
    }

    /**
     * Drain everything available since the persisted checkpoint up to the current head on the calling
     * thread.
     *
     * @return number of envelopes successfully applied during this catch-up pass
     */
    public long catchUp() {
        long applied = 0;
        long from    = checkpoints.load(projection.name()) + 1L;
        long batch   = config.batchSize();
        while (true) {
            EventStream slice = store.readAll(from, batch);
            if (slice.isEmpty())
                break;
            for (EventEnvelope env : slice.events()) {
                projection.apply(env);
                checkpoints.save(projection.name(), env.globalPosition());
                applied++;
            }
            from = slice.lastVersion() + 1L;
            if (slice.size() < batch)
                break;
        }
        return applied;
    }

    /**
     * Start the polling loop on a single daemon thread.
     *
     * @return a future that completes when {@link #close()} stops the runner, or the same in-flight
     *         future if the runner is already started
     */
    public synchronized CompletableFuture<Void> start() {
        CompletableFuture<Void> existing = completionFuture.get();
        if (!running.compareAndSet(false, true)) {
            return existing == null ? CompletableFuture.completedFuture(null) : existing;
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        completionFuture.set(done);
        Thread t =
                Thread.ofPlatform()
                        .daemon(true)
                        .name("nexus-projection-" + projection.name())
                        .unstarted(() -> runLoop(done));
        pollerThread.set(t);
        t.start();
        LOG.log(Level.INFO, () -> "ProjectionRunner started: " + projection.name());
        return done;
    }

    private void runLoop(CompletableFuture<Void> done) {
        try {
            while (running.get()) {
                long applied = catchUp();
                if (applied == 0) {
                    try {
                        //noinspection BusyWait
                        Thread.sleep(config.pollInterval().toMillis());
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, () -> "ProjectionRunner " + projection.name() + " aborted", t);
            done.completeExceptionally(t);
            return;
        } finally {
            running.set(false);
            pollerThread.compareAndSet(Thread.currentThread(), null);
        }
        done.complete(null);
    }

    /** Stop the polling loop and wait briefly for the worker thread to finish. */
    @Override
    public synchronized void close() {
        if (!running.compareAndSet(true, false))
            return;
        LOG.log(Level.INFO, () -> "ProjectionRunner stopping: " + projection.name());
        Thread t = pollerThread.getAndSet(null);
        if (t != null) {
            t.interrupt();
            try {
                t.join(config.shutdownGrace().toMillis());
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
