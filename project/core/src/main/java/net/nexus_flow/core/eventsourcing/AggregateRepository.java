package net.nexus_flow.core.eventsourcing;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import org.jspecify.annotations.Nullable;

/**
 * Loads and saves {@link Aggregate} instances backed by an {@link EventStore}.
 *
 * <p>{@link #load(UUID)} reads every envelope of the aggregate's stream, hands each one to {@link
 * Aggregate#replay(DomainEvent)} (which advances the per-aggregate sequence counter and triggers
 * {@code Aggregate.apply(...)} for state mutation without recording), and sets the committed
 * version to {@code envelopes.size()}. If a {@link SnapshotStore} is configured and a snapshot
 * exists for the stream, replay starts from {@code snapshot.version() + 1} to short-circuit the
 * full history.
 *
 * <p>{@link #save(Aggregate)} reads the aggregate's uncommitted events (snapshot, NOT drain), calls
 * {@link EventStore#append(StreamId, long, List)} with {@code expectedVersion =
 * aggregate.version()} and, on success, calls {@link Aggregate#markCommitted(long)}. On {@link
 * AppendResult.VersionConflict} it raises {@link OptimisticConcurrencyException} <strong>and leaves
 * the aggregate's uncommitted buffer untouched</strong> so the caller can rebase and retry.
 *
 * <p>When configured with both a {@link SnapshotStore} and {@code snapshotEvery(N) > 0}, {@link
 * #save(Aggregate)} persists a snapshot every {@code N} committed versions provided the aggregate
 * overrides {@link Aggregate#captureSnapshotState()}. The hook returns {@link Optional#empty()} by
 * default, so aggregates that do not implement state capture opt out automatically.
 *
 * @param <T> concrete aggregate type
 */
public final class AggregateRepository<T extends Aggregate> {

    private static final Logger LOG = System.getLogger(AggregateRepository.class.getName());

    /**
     * Default cap on stream reads per round-trip — prevents pathological replay. Operators with
     * larger aggregates can raise the cap via {@link Builder#readBatchSize(long)} (e.g. for very
     * long-lived event-sourced aggregates with thousands of historical events).
     */
    public static final long MAX_READ_BATCH = 10_000L;

    private final EventStore              eventStore;
    private final Class<T>                aggregateType;
    private final Supplier<T>             aggregateFactory;
    private final @Nullable SnapshotStore snapshotStore;
    private final int                     snapshotEvery;
    private final long                    readBatchSize;

    /**
     * Create a repository without snapshot support.
     *
     * @param eventStore       backing event store
     * @param aggregateType    aggregate runtime type used to derive the stream id namespace
     * @param aggregateFactory factory that creates empty aggregate instances for hydration
     * @throws NullPointerException if any argument is {@code null}
     */
    public AggregateRepository(
            EventStore eventStore, Class<T> aggregateType, Supplier<T> aggregateFactory) {
        this(eventStore, aggregateType, aggregateFactory, null, 0, MAX_READ_BATCH);
    }

    /**
     * Create a repository that may hydrate from snapshots but does not write them automatically.
     *
     * @param eventStore       backing event store
     * @param aggregateType    aggregate runtime type used to derive the stream id namespace
     * @param aggregateFactory factory that creates empty aggregate instances for hydration
     * @param snapshotStore    optional snapshot store used during {@link #load(UUID)}
     * @throws NullPointerException if {@code eventStore}, {@code aggregateType}, or {@code
     *     aggregateFactory}     is {@code null}
     */
    public AggregateRepository(
            EventStore eventStore,
            Class<T> aggregateType,
            Supplier<T> aggregateFactory,
            @Nullable SnapshotStore snapshotStore) {
        this(eventStore, aggregateType, aggregateFactory, snapshotStore, 0, MAX_READ_BATCH);
    }

    private AggregateRepository(
            EventStore eventStore,
            Class<T> aggregateType,
            Supplier<T> aggregateFactory,
            @Nullable SnapshotStore snapshotStore,
            int snapshotEvery,
            long readBatchSize) {
        this.eventStore       = Objects.requireNonNull(eventStore, "eventStore");
        this.aggregateType    = Objects.requireNonNull(aggregateType, "aggregateType");
        this.aggregateFactory = Objects.requireNonNull(aggregateFactory, "aggregateFactory");
        this.snapshotStore    = snapshotStore; // may be null
        if (snapshotEvery < 0) {
            throw new IllegalArgumentException("snapshotEvery must be >= 0: " + snapshotEvery);
        }
        if (snapshotEvery > 0 && snapshotStore == null) {
            throw new IllegalArgumentException(
                    "snapshotEvery(" + snapshotEvery + ") requires a non-null SnapshotStore");
        }
        if (readBatchSize < 1L) {
            throw new IllegalArgumentException("readBatchSize must be >= 1: " + readBatchSize);
        }
        this.snapshotEvery = snapshotEvery;
        this.readBatchSize = readBatchSize;
    }

    /**
     * Create a builder for fluent configuration of an {@link AggregateRepository}.
     *
     * <p>Fluent API for constructing a repository with custom snapshot settings:
     *
     * <pre>{@code
     * var repo = AggregateRepository.builder(store, MyAggregate.class, MyAggregate::new)
     * .snapshotStore(snapshots)
     * .snapshotEvery(100)
     * .build();
     * }</pre>
     *
     * @param eventStore       the backing event store; non-null
     * @param aggregateType    the aggregate class; non-null
     * @param aggregateFactory factory to instantiate new aggregates for replay; non-null
     * @param <T>              concrete aggregate type
     * @return a new builder
     */
    public static <T extends Aggregate> Builder<T> builder(
            EventStore eventStore, Class<T> aggregateType, Supplier<T> aggregateFactory) {
        return new Builder<>(eventStore, aggregateType, aggregateFactory);
    }

    /**
     * Hydrate an aggregate by replaying its event stream.
     *
     * <p>Load proceeds in four steps:
     *
     * <ol>
     * <li>Create a fresh aggregate instance from {@code aggregateFactory}.
     * <li>If a {@link SnapshotStore} is configured and a snapshot exists, call {@link
     * Aggregate#hydrateFromSnapshot(long)} with the snapshot version, then {@link
     * Aggregate#applySnapshotState(byte[], String)} with the snapshot payload.
     * <li>Read stream pages from {@code snapshot.version() + 1} (or {@code 1} when no snapshot
     * exists) and replay each envelope with {@link Aggregate#replay(DomainEvent)}.
     * <li>Finalize the aggregate's committed version with {@link
     * Aggregate#hydrateFromSnapshot(long)} so the returned instance reflects the latest
     * committed stream version and has no uncommitted events.
     * </ol>
     *
     * @param aggregateId the business id of the aggregate to load; non-null
     * @return a fully hydrated aggregate instance
     * @throws NullPointerException if {@code aggregateId} is {@code null}
     */
    public T load(UUID aggregateId) {
        Objects.requireNonNull(aggregateId, "aggregateId");
        StreamId stream      = streamIdFor(aggregateId);
        T        aggregate   = aggregateFactory.get();
        long     fromVersion = 1L;

        if (snapshotStore != null) {
            var snapshotOpt = snapshotStore.load(stream);
            if (snapshotOpt.isPresent()) {
                Snapshot snapshot = snapshotOpt.get();
                aggregate.hydrateFromSnapshot(snapshot.version());
                aggregate.applySnapshotState(snapshot.state(), snapshot.stateType());
                fromVersion = snapshot.version() + 1L;
                LOG.log(
                        Level.DEBUG,
                        () -> "load(" + stream + "): snapshot hit at version " + snapshot.version());
            }
        }

        long committedVersion  = Math.max(0L, fromVersion - 1L);
        long envelopesReplayed = 0L;
        while (true) {
            EventStream slice = eventStore.read(stream, fromVersion, readBatchSize);
            if (slice.isEmpty())
                break;
            for (EventEnvelope env : slice.events()) {
                aggregate.replay(env.payload());
                committedVersion = env.streamVersion();
                envelopesReplayed++;
            }
            fromVersion = slice.lastVersion() + 1L;
            if (slice.size() < readBatchSize)
                break;
        }
        aggregate.hydrateFromSnapshot(committedVersion);
        final long finalVersion  = committedVersion;
        final long finalReplayed = envelopesReplayed;
        LOG.log(
                Level.DEBUG,
                () -> "load("
                        + stream
                        + "): hydration complete, version="
                        + finalVersion
                        + ", envelopesReplayed="
                        + finalReplayed);
        return aggregate;
    }

    /**
     * Persist the aggregate's uncommitted events to the store.
     *
     * <p>The repository takes the aggregate's current committed {@link Aggregate#version()} as the
     * optimistic-concurrency baseline, appends the current {@link Aggregate#getUncommittedEvents()}
     * batch, and expects the store to reject the write if another writer has already advanced the
     * stream. A successful append is followed by {@link Aggregate#markCommitted(long)} so the
     * in-memory aggregate advances to the exact committed stream version returned by the store.
     *
     * <p>On {@link OptimisticConcurrencyException}, the aggregate's uncommitted buffer is left
     * untouched so the caller can reload the stream, rebase the command, and retry. When snapshotting
     * is enabled, snapshots are written only after the append succeeds.
     *
     * @param aggregate the aggregate instance with uncommitted events; non-null
     * @throws NullPointerException           if {@code aggregate} is {@code null}
     * @throws OptimisticConcurrencyException if the store's version does not match the aggregate's
     *                                        expected version (caller should reload, rebase, and retry)
     */
    public void save(T aggregate) {
        Objects.requireNonNull(aggregate, "aggregate");
        List<DomainEvent> uncommitted = aggregate.getUncommittedEvents();
        if (uncommitted.isEmpty()) {
            return;
        }
        StreamId stream   = streamIdFor(aggregate.getAggregateId());
        long     expected = aggregate.version();
        LOG.log(
                Level.DEBUG,
                () -> "save("
                        + stream
                        + "): appending "
                        + uncommitted.size()
                        + " event(s) at expectedVersion="
                        + expected);
        AppendResult result = eventStore.append(stream, expected, uncommitted);
        switch (result) {
            case AppendResult.Success s                                               -> {
                aggregate.markCommitted(s.newVersion());
                LOG.log(Level.DEBUG, () -> "save(" + stream + "): committed, newVersion=" + s.newVersion());
                maybeWriteSnapshot(aggregate, stream, s.newVersion());
            }
            case AppendResult.VersionConflict(var expectedVersion, var actualVersion) -> {
                LOG.log(
                        Level.WARNING,
                        () -> "save("
                                + stream
                                + "): version conflict, expected="
                                + expectedVersion
                                + " actual="
                                + actualVersion);
                throw new OptimisticConcurrencyException(stream, expectedVersion, actualVersion);
            }
        }
    }

    /**
     * Apply the {@code snapshotEvery(N)} policy. No-op when the policy is disabled, when the
     * aggregate does not implement {@link Aggregate#captureSnapshotState()}, or when {@code
     * newVersion % snapshotEvery != 0}.
     */
    private void maybeWriteSnapshot(T aggregate, StreamId stream, long newVersion) {
        if (snapshotEvery <= 0 || snapshotStore == null) {
            return;
        }
        if (newVersion <= 0 || newVersion % snapshotEvery != 0) {
            return;
        }
        Optional<Aggregate.SnapshotState> stateOpt = aggregate.captureSnapshotState();
        if (stateOpt.isEmpty()) {
            return;
        }
        Aggregate.SnapshotState s = stateOpt.get();
        snapshotStore.save(new Snapshot(stream, newVersion, s.state(), s.stateType()));
    }

    /**
     * Derive the stream identity from an aggregate type and business id.
     *
     * @param aggregateId the business id of the aggregate instance; non-null
     * @return a stream id pairing the aggregate type's binary name with the given id
     * @throws NullPointerException if {@code aggregateId} is {@code null}
     */
    public StreamId streamIdFor(UUID aggregateId) {
        return new StreamId(aggregateType.getName(), aggregateId);
    }

    /**
     * Return the underlying {@link EventStore}.
     *
     * <p>Visible for tests and projection wiring. Callers should not use this to bypass the
     * repository's concurrency guarantees or snapshot policies.
     *
     * @return the event store instance
     */
    public EventStore eventStore() {
        return eventStore;
    }

    /**
     * Return the current snapshot policy threshold.
     *
     * <p>Visible for tests and diagnostics.
     *
     * @return the policy value; {@code 0} means snapshotting is disabled
     */
    public int snapshotEvery() {
        return snapshotEvery;
    }

    // ---------------------------------------------------------------------
    // Builder
    // ---------------------------------------------------------------------

    /**
     * Fluent configuration of an {@link AggregateRepository}, primarily for the auto-snapshot policy.
     * The original constructors remain available; the builder is an additive API.
     */
    public static final class Builder<T extends Aggregate> {
        private final EventStore        eventStore;
        private final Class<T>          aggregateType;
        private final Supplier<T>       aggregateFactory;
        private @Nullable SnapshotStore snapshotStore;
        private int                     snapshotEvery;
        private long                    readBatchSize = MAX_READ_BATCH;

        private Builder(EventStore eventStore, Class<T> aggregateType, Supplier<T> aggregateFactory) {
            this.eventStore       = Objects.requireNonNull(eventStore, "eventStore");
            this.aggregateType    = Objects.requireNonNull(aggregateType, "aggregateType");
            this.aggregateFactory = Objects.requireNonNull(aggregateFactory, "aggregateFactory");
        }

        /**
         * Set the snapshot store. Pass {@code null} to disable snapshotting.
         *
         * @param store the snapshot store, or {@code null}
         * @return this builder for chaining
         */
        public Builder<T> snapshotStore(@Nullable SnapshotStore store) {
            this.snapshotStore = store;
            return this;
        }

        /**
         * Persist a snapshot every {@code n} committed versions on the happy path of {@link
         * AggregateRepository#save(Aggregate)}. Requires a {@link #snapshotStore(SnapshotStore)} to be
         * set; {@code 0} (default) disables the policy.
         *
         * @param n policy threshold; {@code 0} disables snapshotting
         * @return this builder for chaining
         */
        public Builder<T> snapshotEvery(int n) {
            this.snapshotEvery = n;
            return this;
        }

        /**
         * Maximum number of envelopes read per round-trip during aggregate hydration. Defaults to
         * {@link AggregateRepository#MAX_READ_BATCH} (10 000). Raise for very long-lived aggregates
         * that need to replay more events per batch; lower to bound the size of per-batch heap
         * allocations under memory pressure.
         *
         * @param n batch size; must be {@code >= 1}
         * @return this builder for chaining
         */
        public Builder<T> readBatchSize(long n) {
            this.readBatchSize = n;
            return this;
        }

        /**
         * Build the configured {@link AggregateRepository}.
         *
         * @return a new repository instance
         */
        public AggregateRepository<T> build() {
            return new AggregateRepository<>(
                    eventStore, aggregateType, aggregateFactory, snapshotStore, snapshotEvery, readBatchSize);
        }
    }
}
