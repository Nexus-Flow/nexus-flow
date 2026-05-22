package net.nexus_flow.core.cqrs.command;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.PerRuntime;
import net.nexus_flow.core.types.TypeReference;
import org.jspecify.annotations.Nullable;

/**
 * Per-runtime registry of command handler executors ().
 *
 * <p><b>single-map consolidation.</b> A single {@code executorMap} replaces the two parallel {@code
 * ConcurrentHashMap}s, keyed by {@link TypeReference} and valued by {@link CommandExecutorEntry}.
 * The entry kind ({@link CommandExecutorEntry.NoReturnEntry} vs {@link
 * CommandExecutorEntry.ReturnEntry}) distinguishes void / return registrations at the type level.
 *
 * <p><b>unified executor.</b> The wrapped executor type is the single {@link
 * DefaultCommandHandlerExecutor}, which serves both void and value-producing command paths. The
 * discrimination carried by {@link CommandExecutorEntry} is purely a routing / snapshot concern.
 *
 * <p>Public API ({@link CommandConsumerRegistry}) is unchanged in shape; only the concrete return
 * types of {@code get*Publisher} were tightened to the unified executor class.
 */
@PerRuntime
final class DefaultCommandConsumerRegistry implements CommandConsumerRegistry {

    private final Map<TypeReference<?>, CommandExecutorEntry<?>> executorMap =
            new ConcurrentHashMap<>();

    /**
     * Lazily-computed snapshot of {@link #executorMap}'s shape. Hot path readers ({@code
     * RingCommandFallback#isLocallyRegistered}, {@code RuntimeBackedLocalDispatchHandler} per
     * inbound dispatch, observability tooling) hit this cache and avoid the {@code Set.copyOf}
     * x 2 allocation that {@link CommandRegistrationSnapshot}'s compact constructor performs.
     *
     * <p>Cache discipline:
     *
     * <ul>
     * <li>Every mutation method ({@code create*Publisher}, {@code clear*Publisher}) calls
     * {@link #invalidateSnapshot()} BEFORE returning so the next read recomputes.
     * <li>The field is {@code volatile} so the write/clear is visible to other threads
     * without a lock.
     * <li>Two concurrent readers racing to compute after an invalidation each produce an
     * equivalent immutable snapshot; the volatile {@code set} merely picks one.
     * Idempotent races are safe because the snapshot is value-equal — listeners /
     * observers do not observe instance identity.
     * </ul>
     */
    private volatile @Nullable CommandRegistrationSnapshot cachedSnapshot;

    private final EventBus        eventBus;
    private final ExecutorService executor;

    /**
     * Owning runtime; held so the registry can hand it to its handler executors for {@link
     * net.nexus_flow.core.runtime.ExecutionStrategyResolver} resolution without re-introducing a
     * process-wide singleton.
     */
    private final FlowRuntime runtime;

    DefaultCommandConsumerRegistry(EventBus eventBus, ExecutorService executor, FlowRuntime runtime) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.runtime  = Objects.requireNonNull(runtime, "runtime");
    }

    private static IllegalArgumentException duplicateRegistration(TypeReference<?> typeReference) {
        return new IllegalArgumentException(
                "A command handler is already registered for type "
                        + typeReference.getType().getTypeName());
    }

    // No-return path

    /** {@inheritDoc} */
    @Override
    public <T extends Record, H extends NoReturnCommandHandler<T>> void createPublisher(
            TypeReference<T> typeReference, H handler) {
        TypeReference<T> commandType  = Objects.requireNonNull(typeReference, "typeReference");
        H                typedHandler = Objects.requireNonNull(handler, "handler");
        executorMap.compute(
                            commandType,
                            (_, existing) -> {
                                if (existing != null) {
                                    throw duplicateRegistration(commandType);
                                }
                                return new CommandExecutorEntry.NoReturnEntry<>(
                                        DefaultCommandHandlerExecutor.forNoReturn(typedHandler, eventBus, executor, runtime));
                            });
        invalidateSnapshot();
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends Record> @Nullable DefaultCommandHandlerExecutor<T, Void, NoReturnCommandHandler<T>> getNoReturnPublisher(
            TypeReference<T> typeReference) {
        CommandExecutorEntry<?> entry =
                executorMap.get(Objects.requireNonNull(typeReference, "typeReference"));
        if (entry instanceof CommandExecutorEntry.NoReturnEntry<?>(var cmdExec)) {
            @SuppressWarnings("unchecked") DefaultCommandHandlerExecutor<T, Void, NoReturnCommandHandler<T>> typed =
                    (DefaultCommandHandlerExecutor<T, Void, NoReturnCommandHandler<T>>) (DefaultCommandHandlerExecutor<?, ?, ?>) cmdExec;
            return typed;
        }
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public <T extends Record> void clearNoReturnPublisher(TypeReference<T> typeReference) {
        CommandExecutorEntry<?> entry =
                executorMap.remove(Objects.requireNonNull(typeReference, "typeReference"));
        // Invalidate unconditionally on any map mutation: even when the removed entry was a
        // ReturnEntry (which we won't close from this no-return path), the snapshot's set
        // membership changed and the cache must rebuild.
        if (entry != null) {
            invalidateSnapshot();
            if (entry instanceof CommandExecutorEntry.NoReturnEntry<?>(var cmdExec)) {
                cmdExec.close();
            }
        }
    }

    // Return path

    /** {@inheritDoc} */
    @Override
    public <T extends Record, R, H extends ReturnCommandHandler<T, R>> void createPublisher(
            TypeReference<T> typeReference, H handler) {
        TypeReference<T> commandType  = Objects.requireNonNull(typeReference, "typeReference");
        H                typedHandler = Objects.requireNonNull(handler, "handler");
        executorMap.compute(
                            commandType,
                            (_, existing) -> {
                                if (existing != null) {
                                    throw duplicateRegistration(commandType);
                                }
                                return new CommandExecutorEntry.ReturnEntry<>(
                                        DefaultCommandHandlerExecutor.forReturn(typedHandler, eventBus, executor, runtime));
                            });
        invalidateSnapshot();
    }

    /** {@inheritDoc} */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends Record, R> @Nullable DefaultCommandHandlerExecutor<T, R, ReturnCommandHandler<T, R>> getReturnPublisher(
            TypeReference<T> typeReference) {
        CommandExecutorEntry<?> entry =
                executorMap.get(Objects.requireNonNull(typeReference, "typeReference"));
        if (entry instanceof CommandExecutorEntry.ReturnEntry<?, ?>(var cmdExec)) {
            return (DefaultCommandHandlerExecutor<T, R, ReturnCommandHandler<T, R>>) (DefaultCommandHandlerExecutor<?, ?, ?>) cmdExec;
        }
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public <T extends Record, R> void clearReturnPublisher(TypeReference<T> typeReference) {
        CommandExecutorEntry<?> entry =
                executorMap.remove(Objects.requireNonNull(typeReference, "typeReference"));
        if (entry != null) {
            invalidateSnapshot();
            if (entry instanceof CommandExecutorEntry.ReturnEntry<?, ?>(var cmdExec)) {
                cmdExec.close();
            }
        }
    }

    // Lifecycle

    /** {@inheritDoc} */
    @Override
    public void closeAll() {
        for (TypeReference<?> key : executorMap.keySet().toArray(new TypeReference<?>[0])) {
            CommandExecutorEntry<?> entry = executorMap.remove(key);
            if (entry != null) {
                entry.close();
            }
        }
    }

    // Snapshot

    /** {@inheritDoc} */
    @Override
    public CommandRegistrationSnapshot snapshot() {
        CommandRegistrationSnapshot cached = cachedSnapshot;
        if (cached != null) {
            return cached;
        }
        Set<TypeReference<?>> noReturnTypes = new HashSet<>();
        Set<TypeReference<?>> returnTypes   = new HashSet<>();
        for (Map.Entry<TypeReference<?>, CommandExecutorEntry<?>> e : executorMap.entrySet()) {
            switch (e.getValue()) {
                case CommandExecutorEntry.NoReturnEntry<?> _  -> noReturnTypes.add(e.getKey());
                case CommandExecutorEntry.ReturnEntry<?, ?> _ -> returnTypes.add(e.getKey());
            }
        }
        CommandRegistrationSnapshot fresh =
                new CommandRegistrationSnapshot(Set.copyOf(noReturnTypes), Set.copyOf(returnTypes));
        cachedSnapshot = fresh;
        return fresh;
    }

    /** Mark the cached snapshot stale — called by every {@link #executorMap} mutation. */
    private void invalidateSnapshot() {
        cachedSnapshot = null;
    }
}
