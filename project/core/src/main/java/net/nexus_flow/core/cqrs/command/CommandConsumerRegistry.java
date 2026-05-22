package net.nexus_flow.core.cqrs.command;

import net.nexus_flow.core.runtime.PerRuntime;
import net.nexus_flow.core.types.TypeReference;
import org.jspecify.annotations.Nullable;

@PerRuntime
interface CommandConsumerRegistry {

    /**
     * Register a no-return handler for the given command type, wrapping it in a freshly-built
     * executor pipeline. Replaces any previous registration for the same {@link TypeReference}.
     */
    <T extends Record, H extends NoReturnCommandHandler<T>> void createPublisher(
            TypeReference<T> typeReference, H handler);

    /**
     * @return the executor currently registered for the given no-return command type, or {@code null}
     *         if none has been registered.
     */
    <T extends Record> @Nullable DefaultCommandHandlerExecutor<T, Void, NoReturnCommandHandler<T>> getNoReturnPublisher(
            TypeReference<T> typeReference);

    /**
     * Unregister and close the no-return executor for the given command type. No-op when no publisher
     * is currently registered.
     */
    <T extends Record> void clearNoReturnPublisher(TypeReference<T> typeReference);

    /**
     * Register a return-value handler for the given command type, wrapping it in a freshly-built
     * executor pipeline. Replaces any previous registration for the same {@link TypeReference}.
     */
    <T extends Record, R, H extends ReturnCommandHandler<T, R>> void createPublisher(
            TypeReference<T> typeReference, H handler);

    /**
     * @return the executor currently registered for the given return command type, or {@code null} if
     *         none has been registered.
     */
    <T extends Record, R> @Nullable DefaultCommandHandlerExecutor<T, R, ReturnCommandHandler<T, R>> getReturnPublisher(
            TypeReference<T> typeReference);

    /**
     * Unregister and close the return executor for the given command type. No-op when no publisher is
     * currently registered.
     */
    <T extends Record, R> void clearReturnPublisher(TypeReference<T> typeReference);

    /**
     * release every registered handler executor. Called from {@code DefaultFlowRuntime#close()} so
     * handlers scoped to one runtime never leak into another.
     */
    void closeAll();

    /**
     * read-only diagnostic view of the registry, mirroring the shape of {@link
     * net.nexus_flow.core.runtime.registry.DispatchPlan DispatchPlan} for event listeners.
     */
    CommandRegistrationSnapshot snapshot();
}
