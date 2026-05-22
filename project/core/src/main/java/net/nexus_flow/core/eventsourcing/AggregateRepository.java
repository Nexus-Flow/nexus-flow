package net.nexus_flow.core.eventsourcing;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.outbox.OutboxAppender;
import net.nexus_flow.core.outbox.OutboxConfig;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowScope;
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
    @SuppressWarnings("PMD.UnusedPrivateField") // retained for type-parameter binding clarity; getName() is cached in aggregateTypeName
    private final Class<T>                aggregateType;
    private final Supplier<T>             aggregateFactory;
    private final @Nullable SnapshotStore snapshotStore;
    private final int                     snapshotEvery;
    private final long                    readBatchSize;
    private final @Nullable OutboxConfig  outboxConfig;
    /**
     * Cached binary name of {@link #aggregateType}, computed once at construction time.
     * {@link Class#getName()} returns a cached String on JDK side but {@link #streamIdFor(UUID)}
     * is on the per-save hot path and accessing the cached field avoids the virtual call.
     */
    private final String                  aggregateTypeName;

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
        this(eventStore, aggregateType, aggregateFactory, null, 0, MAX_READ_BATCH, null);
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
        this(eventStore, aggregateType, aggregateFactory, snapshotStore, 0, MAX_READ_BATCH, null);
    }

    private AggregateRepository(
            EventStore eventStore,
            Class<T> aggregateType,
            Supplier<T> aggregateFactory,
            @Nullable SnapshotStore snapshotStore,
            int snapshotEvery,
            long readBatchSize,
            @Nullable OutboxConfig outboxConfig) {
        this.eventStore        = Objects.requireNonNull(eventStore, "eventStore");
        this.aggregateType     = Objects.requireNonNull(aggregateType, "aggregateType");
        this.aggregateTypeName = aggregateType.getName();
        this.aggregateFactory  = Objects.requireNonNull(aggregateFactory, "aggregateFactory");
        this.snapshotStore     = snapshotStore; // may be null
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
        this.outboxConfig  = outboxConfig; // may be null
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
            // Batch-replay: acquire the aggregate's lifecycle lock ONCE for the whole slice
            // instead of once per event. Saves 20–50 ns × N events on hot replay paths;
            // semantics are identical to single-event replay (per-event apply() + sequence
            // advance under the surrounding lock).
            int sliceSize = slice.size();
            if (sliceSize == 1) {
                EventEnvelope env = slice.events().get(0);
                aggregate.replay(env.payload());
                committedVersion = env.streamVersion();
                envelopesReplayed++;
            } else {
                List<DomainEvent> batch = new ArrayList<>(sliceSize);
                for (EventEnvelope env : slice.events()) {
                    batch.add(env.payload());
                }
                aggregate.replayAll(batch);
                committedVersion   = slice.events().get(sliceSize - 1).streamVersion();
                envelopesReplayed += sliceSize;
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
     * Persist the aggregate's uncommitted events to the store, and (when an outbox is bound)
     * append the same batch to the outbox for downstream durable delivery.
     *
     * <p>The repository takes the aggregate's current committed {@link Aggregate#version()} as the
     * optimistic-concurrency baseline, appends the current {@link Aggregate#getUncommittedEvents()}
     * batch, and expects the store to reject the write if another writer has already advanced the
     * stream. A successful append is followed by:
     *
     * <ol>
     * <li>Outbox append (if {@link Builder#outbox(OutboxConfig)} was configured) — every event
     * in the batch is rolled into the outbox storage under the {@link FlowScope}-current
     * {@link ExecutionContext} so trace / correlation / causation / message ids travel
     * end-to-end. If no scope is bound the repository falls back to {@link
     * ExecutionContext#root()} — sufficient for batch jobs but adapter modules (Spring,
     * Quarkus) typically wire a scope per request so the wire ids match the calling
     * command's.
     * <li>{@link Aggregate#markCommitted(long)} on the supplied aggregate so its in-memory
     * view advances to the exact committed stream version returned by the store.
     * <li>Optional snapshot if the {@code snapshotEvery(N)} policy matches the new version.
     * </ol>
     *
     * <p>On {@link OptimisticConcurrencyException}, the aggregate's uncommitted buffer is left
     * untouched so the caller can reload the stream, rebase the command, and retry. Snapshot
     * writes and outbox appends only happen after the store-side append succeeds.
     *
     * <p><strong>Atomicity caveat (in-tree).</strong> The in-tree implementation does not wrap
     * the event-store append and the outbox append in a single transaction — the in-memory
     * stores do not support one. JDBC adapter modules execute both writes inside the same
     * connection transaction (the canonical "transactional outbox" pattern). When the outbox
     * append fails AFTER the event-store append, the aggregate is NOT marked committed: callers
     * see an exception and the aggregate's uncommitted buffer is preserved so a reload + rebase
     * + retry path stays valid. The events ARE in the event store, so the next save attempt
     * will see a version conflict — the operator reconciles by reloading from the store.
     *
     * @param aggregate the aggregate instance with uncommitted events; non-null
     * @throws NullPointerException           if {@code aggregate} is {@code null}
     * @throws OptimisticConcurrencyException if the store's version does not match the aggregate's
     *                                        expected version (caller should reload, rebase, and retry)
     * @throws RuntimeException               if the outbox append fails after the store-side append
     *                                        committed (the aggregate is left in its pre-save state — events ARE in the store; the
     *                                        operator reconciles by reloading)
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
                if (outboxConfig != null) {
                    appendToOutbox(uncommitted);
                }
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
     * Append the {@code uncommitted} batch to the configured outbox. The {@link ExecutionContext}
     * is read from {@link FlowScope#current()} so the row's trace / correlation / causation /
     * message ids match the dispatching command's. A root context is used when no scope is
     * bound — appropriate for batch jobs or test fixtures.
     */
    private void appendToOutbox(List<DomainEvent> uncommitted) {
        // outboxConfig is already null-checked by the {@code if (outboxConfig != null)} guard
        // at the only caller (save()), and stored in a final field — re-checking on every save
        // would waste a JIT-inlined nullcheck on the per-event hot path.
        OutboxConfig     cfg = outboxConfig;
        ExecutionContext ctx = FlowScope.current().orElseGet(ExecutionContext::root);
        OutboxAppender.appendDrainedEvents(uncommitted, ctx, cfg.storage(), cfg.clock(), cfg.codec());
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
        return new StreamId(aggregateTypeName, aggregateId);
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
        private @Nullable OutboxConfig  outboxConfig;

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
         * Bind an {@link OutboxConfig} so {@link AggregateRepository#save(Aggregate)} appends
         * the same uncommitted batch to the outbox storage as part of the save. Closes the
         * "repository pattern doesn't touch the outbox" gap so handlers using the repository
         * no longer have to call {@link OutboxAppender#appendDrainedEvents} by hand.
         *
         * <p>The trace / correlation / causation / message ids on each row come from the
         * {@link FlowScope}-current execution context (root when no scope is bound).
         *
         * <p>Pass {@code null} to disable the integration (default).
         */
        public Builder<T> outbox(@Nullable OutboxConfig outboxConfig) {
            this.outboxConfig = outboxConfig;
            return this;
        }

        /**
         * Build the configured {@link AggregateRepository}.
         *
         * @return a new repository instance
         */
        public AggregateRepository<T> build() {
            return new AggregateRepository<>(
                    eventStore,
                    aggregateType,
                    aggregateFactory,
                    snapshotStore,
                    snapshotEvery,
                    readBatchSize,
                    outboxConfig);
        }
    }
}
