package net.nexus_flow.core.eventsourcing;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
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

    private final EventStore                        store;
    private final ProjectionCheckpointStore         checkpoints;
    private final Projection                        projection;
    private final ProjectionRunnerConfig            config;
    private final @Nullable ProjectionSnapshotStore snapshots;
    /**
     * Per-runtime snapshot policy: capture a snapshot every {@code snapshotEvery} envelopes
     * applied since the last one. {@code 0} means "never snapshot" — the default for
     * projections without a snapshot store wired.
     */
    private final int                               snapshotEvery;
    private volatile boolean                        snapshotApplied;
    private long                                    envelopesSinceLastSnapshot;

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
        this(store, checkpoints, projection, config, null, 0);
    }

    /**
     * Snapshot-aware constructor. Pass a non-null {@code snapshots} store and a positive
     * {@code snapshotEvery} value to opt the runner into the snapshot path:
     *
     * <ul>
     * <li>On the first {@link #catchUp()} call after construction, the runner loads the
     * latest snapshot for this projection's name (if present), invokes
     * {@link Projection#applySnapshotState(byte[], String)}, advances the local
     * checkpoint baseline to {@code snapshot.globalPosition()}, and resumes envelope
     * replay from {@code globalPosition + 1}.
     * <li>Every {@code snapshotEvery} envelopes applied, the runner asks the projection for
     * a fresh {@link Projection#captureSnapshotState()} and persists it through
     * {@link ProjectionSnapshotStore#save(ProjectionSnapshot)}. Projections whose
     * {@code captureSnapshotState()} returns {@link Optional#empty()} silently skip the
     * write — they opt out of snapshotting.
     * </ul>
     *
     * @param store         the event store to read from; non-null
     * @param checkpoints   checkpoint store for persisting progress; non-null
     * @param projection    the projection to apply envelopes to; non-null
     * @param config        tuning configuration; non-null
     * @param snapshots     optional snapshot store; {@code null} disables snapshotting
     * @param snapshotEvery how often (in envelopes applied) to capture a fresh snapshot; must
     *                      be {@code >= 0}. {@code 0} disables periodic snapshotting (the
     *                      load-on-start path remains active when the store is non-null)
     * @throws IllegalArgumentException if {@code snapshotEvery > 0} and {@code snapshots} is
     *                                  {@code null}
     */
    public ProjectionRunner(
            EventStore store,
            ProjectionCheckpointStore checkpoints,
            Projection projection,
            ProjectionRunnerConfig config,
            @Nullable ProjectionSnapshotStore snapshots,
            int snapshotEvery) {
        this.store       = Objects.requireNonNull(store, "store");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.projection  = Objects.requireNonNull(projection, "projection");
        this.config      = Objects.requireNonNull(config, "config");
        if (snapshotEvery < 0) {
            throw new IllegalArgumentException(
                    "snapshotEvery must be >= 0: " + snapshotEvery);
        }
        if (snapshotEvery > 0 && snapshots == null) {
            throw new IllegalArgumentException(
                    "snapshotEvery(" + snapshotEvery + ") requires a non-null ProjectionSnapshotStore");
        }
        this.snapshots                  = snapshots;
        this.snapshotEvery              = snapshotEvery;
        this.snapshotApplied            = false;
        this.envelopesSinceLastSnapshot = 0L;
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
        // First catch-up after construction: if a snapshot store is wired, load the latest
        // snapshot into the projection and advance the checkpoint baseline so we resume from
        // snapshot.globalPosition() + 1 instead of the persisted checkpoint (which is at
        // most equal, and could legitimately be 0 if the checkpoint store was wiped).
        if (snapshots != null && !snapshotApplied) {
            Optional<ProjectionSnapshot> latest = snapshots.load(projection.name());
            if (latest.isPresent()) {
                ProjectionSnapshot snap = latest.get();
                projection.applySnapshotState(snap.state(), snap.stateType());
                if (snap.globalPosition() > checkpoints.load(projection.name())) {
                    checkpoints.save(projection.name(), snap.globalPosition());
                }
                LOG.log(Level.DEBUG,
                        () -> "ProjectionRunner " + projection.name()
                                + " rehydrated from snapshot at globalPosition="
                                + snap.globalPosition());
            }
            snapshotApplied = true;
        }
        long from  = checkpoints.load(projection.name()) + 1L;
        long batch = config.batchSize();
        while (true) {
            EventStream slice = store.readAll(from, batch);
            if (slice.isEmpty()) {
                break;
            }
            // Batch the checkpoint write: apply every envelope in the slice, then persist a
            // single checkpoint at the last envelope's globalPosition. Per-envelope
            // checkpoint writes amplified the storage cost N-fold (a ConcurrentHashMap.merge
            // call per envelope on the in-memory backend, an UPDATE per envelope on JDBC).
            // The batched write is equivalent under at-least-once semantics: a crash
            // mid-slice replays the entire slice, which is what the at-least-once contract
            // already requires.
            for (EventEnvelope env : slice.events()) {
                projection.apply(env);
                applied++;
                envelopesSinceLastSnapshot++;
                maybeWriteSnapshot(env.globalPosition());
            }
            checkpoints.save(projection.name(), slice.events().getLast().globalPosition());
            from = slice.lastVersion() + 1L;
            if (slice.size() < batch) {
                break;
            }
        }
        return applied;
    }

    /**
     * Honour the {@code snapshotEvery(N)} policy. No-op when snapshotting is disabled, when
     * the projection opts out (returns {@link Optional#empty()} from {@link
     * Projection#captureSnapshotState()}), or when the threshold has not been crossed.
     */
    private void maybeWriteSnapshot(long globalPosition) {
        if (snapshotEvery <= 0 || snapshots == null) {
            return;
        }
        if (envelopesSinceLastSnapshot < (long) snapshotEvery) {
            return;
        }
        Optional<Projection.CapturedState> captured = projection.captureSnapshotState();
        if (captured.isEmpty()) {
            // Projection doesn't snapshot; reset the counter so we don't re-ask on every
            // envelope. Adapter-driven projections may flip-flop between supporting and not
            // supporting snapshots across releases; the counter reset keeps the policy
            // proportionate either way.
            envelopesSinceLastSnapshot = 0L;
            return;
        }
        Projection.CapturedState state = captured.get();
        snapshots.save(new ProjectionSnapshot(
                projection.name(), globalPosition, state.state(), state.stateType()));
        envelopesSinceLastSnapshot = 0L;
        LOG.log(Level.DEBUG,
                () -> "ProjectionRunner " + projection.name()
                        + " saved snapshot at globalPosition=" + globalPosition);
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
