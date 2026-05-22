package net.nexus_flow.core.cqrs.command;

import net.nexus_flow.core.types.TypeReference;

/**
 * Package-private marker for handlers that, despite implementing the {@code *Internal} variant of
 * the sealed handler hierarchy, still carry their {@link TypeReference} at runtime.
 *
 * <p>Used by {@link DefaultCommandBus} to route builder-produced handlers (which implement {@link
 * NoReturnCommandHandlerInternal} or {@link ReturnCommandHandlerInternal} directly, rather than
 * extending {@link AbstractNoReturnCommandHandler} / {@link AbstractReturnCommandHandler}) without
 * losing their command type.
 *
 * @param <T> the command payload type
 */
@FunctionalInterface
interface CommandTypeAware<T extends Record> {
    /**
     * @return the {@link TypeReference} for the command record this handler accepts; used by {@link
     *         DefaultCommandBus} to route incoming commands to the right executor.
     */
    TypeReference<T> getCommandType();
}
