package net.nexus_flow.core.cqrs.command;

/**
 * Unified entry type for the single-map consolidation of {@link DefaultCommandConsumerRegistry}.
 *
 * <p>The two parallel registry maps are collapsed into one keyed by {@link
 * net.nexus_flow.core.types.TypeReference}, with this sealed wrapper distinguishing the handler
 * kind at the type level (pattern-match instead of {@code instanceof} cascades).
 *
 * <p>Both permits below wrap the <em>same</em> executor class ({@link
 * DefaultCommandHandlerExecutor}); the discriminator survives so {@link
 * CommandRegistrationSnapshot} keeps reporting the two type sets without having to peek inside the
 * executor's {@code voidPath} flag.
 *
 * <p>This is a package-private internal type; public API consumers only see {@link
 * CommandRegistrationSnapshot} and the {@link CommandBus} methods.
 */
sealed interface CommandExecutorEntry<T extends Record>
        permits CommandExecutorEntry.NoReturnEntry, CommandExecutorEntry.ReturnEntry {

    /** Underlying unified executor. */
    DefaultCommandHandlerExecutor<T, ?, ?> executor();

    /** Fire-and-forget executor entry. */
    record NoReturnEntry<T extends Record>(
                                           DefaultCommandHandlerExecutor<T, Void, NoReturnCommandHandler<T>> executor)
            implements CommandExecutorEntry<T> {
    }

    /** Value-producing executor entry. */
    record ReturnEntry<T extends Record, R>(
                                            DefaultCommandHandlerExecutor<T, R, ReturnCommandHandler<T, R>> executor)
            implements CommandExecutorEntry<T> {
    }

    /** Close the underlying executor (flips running flag and drains queue). */
    default void close() {
        executor().close();
    }

    /** {@code true} when the underlying executor reports it is still running. */
    default boolean isRunning() {
        return executor().isRunning();
    }
}
