package net.nexus_flow.core.saga;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * In-memory {@link SagaStorage} for tests and single-node demos.
 *
 * <h2>Concurrency model</h2>
 *
 * Data is held in a {@link ConcurrentHashMap} of per-instance {@link Slot}s. Each slot
 * owns a {@link ReentrantLock} that serialises concurrent {@link #save}, {@link
 * #tryAcquireOwnership}, and {@link #loadOwnership} calls for the same
 * {@code (type, correlationKey)} key, guaranteeing atomic state + ownership transitions.
 * {@link #load} is lock-free.
 *
 * <h2>Push observation</h2>
 *
 * {@link #subscribe} installs a {@link SagaStorageObserver}; every successful {@link
 * #save} invokes the observer synchronously inside the per-saga lock so a subscriber
 * sees a consistent post-write state. Observers must be fast — they run under the lock.
 *
 * <h2>Production use</h2>
 *
 * Not for production — no durability across restarts. Replace with a JDBC/Redis backend
 * for multi-node deployments. The full SPI (including {@link #tryAcquireOwnership} and
 * {@link #subscribe}) is required by ring-integrated deployments; this implementation
 * exists primarily so tests and single-node demos can exercise every code path without an
 * external storage dependency.
 */
public final class InMemorySagaStorage implements SagaStorage {

    private record Key(String type, String correlationKey) {
        Key {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(correlationKey, "correlationKey");
        }
    }

    /** Per-instance slot holding the current state, ownership, and mutation lock. */
    private static final class Slot {
        /** Serialises concurrent {@link #save} / {@link #tryAcquireOwnership} calls for this key. */
        final ReentrantLock lock = new ReentrantLock();

        /** Current saga state; {@code null} until the first successful save. */
        final AtomicReference<@Nullable SagaState> state = new AtomicReference<>();

        /**
         * Current ownership — {@link SagaOwnership#UNOWNED} until the first acquire. Mutated
         * only by {@link #tryAcquireOwnership} under {@link #lock}; reads are lock-free.
         */
        final AtomicReference<SagaOwnership> ownership = new AtomicReference<>(SagaOwnership.UNOWNED);
    }

    private final Map<Key, Slot> slots = new ConcurrentHashMap<>();

    /**
     * Registered observers — copy-on-write so the save path iterates without lock contention
     * with subscribe/unsubscribe.
     */
    private final CopyOnWriteArrayList<SagaStorageObserver> observers = new CopyOnWriteArrayList<>();

    /** Lock-free load — reads the latest persisted state via the slot's {@link AtomicReference}. */
    @Override
    public Optional<SagaState> load(String type, String correlationKey) {
        Slot slot = slots.get(new Key(type, correlationKey));
        return slot == null ? Optional.empty() : Optional.ofNullable(slot.state.get());
    }

    /**
     * Persists {@code state} under the optimistic-concurrency constraint that the stored
     * version equals {@code expectedVersion}. Observers receive the post-write state
     * synchronously inside the per-saga lock so they never see a stale snapshot.
     */
    @Override
    public void save(SagaState state, long expectedVersion) {
        Objects.requireNonNull(state, "state");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be >= 0: " + expectedVersion);
        }
        Object corr = state.data().get("_correlationKey");
        if (corr == null) {
            throw new IllegalArgumentException(
                    "SagaState.data must carry a non-null '_correlationKey' entry; got " + state);
        }
        Key  key  = new Key(state.type(), corr.toString());
        Slot slot = slots.computeIfAbsent(key, _ -> new Slot());
        slot.lock.lock();
        try {
            long current = slot.state.get() == null ? 0L : slot.state.get().version();
            if (current != expectedVersion) {
                throw new SagaConcurrencyException(state.id(), expectedVersion, current);
            }
            slot.state.set(state);
            notifyObservers(key, state);
        } finally {
            slot.lock.unlock();
        }
    }

    /**
     * Atomic compare-and-set on ownership. Acquires the per-saga lock so the read-test-write
     * sequence is serialised against any concurrent {@link #save} / {@link #tryAcquireOwnership}
     * for the same saga.
     */
    @Override
    public OwnershipClaimResult tryAcquireOwnership(
            String sagaType,
            String correlationKey,
            String claimant,
            Instant newLeaseExpiry,
            Instant now) {
        Objects.requireNonNull(sagaType, "sagaType");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(claimant, "claimant");
        if (claimant.isBlank()) {
            throw new IllegalArgumentException("claimant must not be blank");
        }
        Objects.requireNonNull(newLeaseExpiry, "newLeaseExpiry");
        Objects.requireNonNull(now, "now");

        Key  key  = new Key(sagaType, correlationKey);
        Slot slot = slots.get(key);
        if (slot == null || slot.state.get() == null) {
            return OwnershipClaimResult.SagaUnknown.INSTANCE;
        }
        slot.lock.lock();
        try {
            SagaOwnership current = slot.ownership.get();
            boolean       canTake =
                    current.isUnowned() || current.isExpired(now) || claimant.equals(current.ownerPeerId());
            if (!canTake) {
                return new OwnershipClaimResult.AlreadyHeldByOther(current);
            }
            SagaOwnership granted =
                    new SagaOwnership(claimant, newLeaseExpiry, current.fencingToken() + 1L);
            slot.ownership.set(granted);
            return new OwnershipClaimResult.Acquired(granted);
        } finally {
            slot.lock.unlock();
        }
    }

    /** Read the current ownership record without locking. */
    @Override
    public Optional<SagaOwnership> loadOwnership(String sagaType, String correlationKey) {
        Slot slot = slots.get(new Key(sagaType, correlationKey));
        if (slot == null) {
            return Optional.empty();
        }
        SagaOwnership current = slot.ownership.get();
        return current.isUnowned() ? Optional.empty() : Optional.of(current);
    }

    /**
     * Register {@code observer} for synchronous push notifications on every {@link #save}.
     * The returned subscription is closed via {@link SagaStorageObserver.Subscription#close()};
     * close is idempotent.
     */
    @Override
    public SagaStorageObserver.Subscription subscribe(SagaStorageObserver observer) {
        Objects.requireNonNull(observer, "observer");
        observers.add(observer);
        return new Subscription(observer);
    }

    private final class Subscription implements SagaStorageObserver.Subscription {
        private final SagaStorageObserver observer;
        private volatile boolean          closed;

        Subscription(SagaStorageObserver observer) {
            this.observer = observer;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                observers.remove(observer);
            }
        }
    }

    private void notifyObservers(Key key, SagaState state) {
        if (observers.isEmpty()) {
            return;
        }
        for (SagaStorageObserver o : observers) {
            try {
                o.onSagaStateChanged(key.type(), key.correlationKey(), state);
            } catch (Throwable ignored) {
                // Observer failures must not poison the saver. JDBC backends would forward
                // through their NOTIFY mechanism which has its own isolation.
            }
        }
    }
}
