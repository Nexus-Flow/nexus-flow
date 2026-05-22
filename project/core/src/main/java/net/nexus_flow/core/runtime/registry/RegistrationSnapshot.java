package net.nexus_flow.core.runtime.registry;

import java.util.*;

/**
 * immutable copy-on-write snapshot of every registration known to a {@link HandlerRegistry}.
 *
 * <p>Keyed by the concrete message {@link Class}; each entry holds the ordered list of {@link
 * RegisteredHandler} records in registration order. {@link HandlerRegistry} swaps the snapshot
 * atomically on {@code register} / {@code unregister} and rebuilds the {@code
 * ClassValue<DispatchPlan>} cache. Producers therefore never mutate a published snapshot — they
 * build a new one.
 *
 * <p>This class is package-private because only {@link HandlerRegistry} should construct it.
 */
final class RegistrationSnapshot {

    /** A single registration. {@code sequence} preserves FIFO across the whole registry. */
    record RegisteredHandler<M, R>(
                                   int sequence, int order, boolean parallelSafe, HandlerInvoker<M, R> invoker) {
    }

    static final RegistrationSnapshot EMPTY = new RegistrationSnapshot(Collections.emptyMap(), 0);

    private final Map<Class<?>, List<RegisteredHandler<?, ?>>> byType;

    /** Monotonic counter used to assign {@code sequence} on next add. */
    private final int nextSequence;

    private RegistrationSnapshot(
            Map<Class<?>, List<RegisteredHandler<?, ?>>> byType, int nextSequence) {
        this.byType       = byType;
        this.nextSequence = nextSequence;
    }

    @SuppressWarnings("unchecked")
    <M, R> List<RegisteredHandler<M, R>> handlersFor(Class<?> type) {
        List<RegisteredHandler<?, ?>> raw = byType.get(type);
        if (raw == null) {
            return Collections.emptyList();
        }
        // Already an unmodifiable list from the builder; cast through
        // the raw shape since RegisteredHandler is invariant in M/R.
        return (List<RegisteredHandler<M, R>>) (List<?>) raw;
    }

    int nextSequence() {
        return nextSequence;
    }

    /** Return a new snapshot with {@code handler} appended for {@code type}. */
    <M, R> RegistrationSnapshot withRegistration(
            Class<?> type, int order, HandlerInvoker<M, R> invoker) {
        return withRegistration(type, order, /* parallelSafe= */ false, invoker);
    }

    /**
     * parallel-aware overload. The {@code parallelSafe} flag is propagated into the {@link
     * RegisteredHandler} so the downstream {@link DispatchPlan} can derive {@code allParallelSafe}.
     */
    <M, R> RegistrationSnapshot withRegistration(
            Class<?> type, int order, boolean parallelSafe, HandlerInvoker<M, R> invoker) {
        Map<Class<?>, List<RegisteredHandler<?, ?>>> next     = new LinkedHashMap<>(byType);
        List<RegisteredHandler<?, ?>>                existing = next.get(type);
        List<RegisteredHandler<?, ?>>                updated;
        RegisteredHandler<M, R>                      fresh    =
                new RegisteredHandler<>(nextSequence, order, parallelSafe, invoker);
        if (existing == null || existing.isEmpty()) {
            updated = List.of(fresh);
        } else {
            List<RegisteredHandler<?, ?>> tmp = new ArrayList<>(existing.size() + 1);
            tmp.addAll(existing);
            tmp.add(fresh);
            updated = List.copyOf(tmp);
        }
        next.put(type, updated);
        return new RegistrationSnapshot(Collections.unmodifiableMap(next), nextSequence + 1);
    }

    /** Return a new snapshot with every registration for {@code type} removed. */
    RegistrationSnapshot withoutType(Class<?> type) {
        if (!byType.containsKey(type)) {
            return this;
        }
        Map<Class<?>, List<RegisteredHandler<?, ?>>> next = new LinkedHashMap<>(byType);
        next.remove(type);
        return new RegistrationSnapshot(Collections.unmodifiableMap(next), nextSequence);
    }

    /**
     * Return a new snapshot with exactly one registration removed.
     *
     * <p>Removal is by invoker identity/equality inside a concrete type bucket; if no matching
     * invoker exists this snapshot is returned unchanged.
     */
    RegistrationSnapshot withoutInvoker(Class<?> type, HandlerInvoker<?, ?> invoker) {
        List<RegisteredHandler<?, ?>> existing = byType.get(type);
        if (existing == null || existing.isEmpty()) {
            return this;
        }

        List<RegisteredHandler<?, ?>> kept    = new ArrayList<>(existing.size());
        boolean                       removed = false;
        for (RegisteredHandler<?, ?> h : existing) {
            if (!removed && Objects.equals(h.invoker(), invoker)) {
                removed = true;
                continue;
            }
            kept.add(h);
        }
        if (!removed) {
            return this;
        }

        Map<Class<?>, List<RegisteredHandler<?, ?>>> next = new LinkedHashMap<>(byType);
        if (kept.isEmpty()) {
            next.remove(type);
        } else {
            next.put(type, List.copyOf(kept));
        }
        return new RegistrationSnapshot(Collections.unmodifiableMap(next), nextSequence);
    }

    /** All known types; useful for bulk-invalidating the {@code ClassValue} cache. */
    Iterable<Class<?>> knownTypes() {
        return byType.keySet();
    }

    /** Set view of known types — same data, typed for defensive copy. */
    java.util.Set<Class<?>> knownTypesSet() {
        return byType.keySet();
    }
}
