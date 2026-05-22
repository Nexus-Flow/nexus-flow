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
        if (entry instanceof CommandExecutorEntry.NoReturnEntry<?>(var cmdExec)) {
            cmdExec.close();
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
        if (entry instanceof CommandExecutorEntry.ReturnEntry<?, ?>(var cmdExec)) {
            cmdExec.close();
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
        Set<TypeReference<?>> noReturnTypes = new HashSet<>();
        Set<TypeReference<?>> returnTypes   = new HashSet<>();
        for (Map.Entry<TypeReference<?>, CommandExecutorEntry<?>> e : executorMap.entrySet()) {
            switch (e.getValue()) {
                case CommandExecutorEntry.NoReturnEntry<?> _  -> noReturnTypes.add(e.getKey());
                case CommandExecutorEntry.ReturnEntry<?, ?> _ -> returnTypes.add(e.getKey());
            }
        }
        return new CommandRegistrationSnapshot(Set.copyOf(noReturnTypes), Set.copyOf(returnTypes));
    }
}
