package net.nexus_flow.core.cqrs.command;

import java.util.Set;
import net.nexus_flow.core.types.TypeReference;

/**
 * read-only diagnostic view of the per-runtime command-handler registry.
 *
 * <p>Mirrors the shape of {@link net.nexus_flow.core.runtime.registry.DispatchPlan DispatchPlan} on
 * the event side: a single immutable snapshot of which command types currently have a handler bound
 * on the owning {@link CommandBus}. Two parallel sets are surfaced because the registry preserves
 * the distinction between fire-and-forget {@link NoReturnCommandHandler}s and value-producing
 * {@link ReturnCommandHandler}s even though registrations share one backing map (see {@link
 * DefaultCommandConsumerRegistry}); a command type appears in at most one set at a time.
 *
 * <p>This is the {@code HandlerRegistry}-shaped consolidation entry point pinned by V15.8.b: it
 * gives observability / debug tooling the same access shape across commands and events without
 * forcing a deep refactor of the executor pipeline (which carries the concurrency-level,
 * back-pressure gate, and lifecycle that event-side {@link
 * net.nexus_flow.core.runtime.registry.HandlerInvoker HandlerInvoker}s do not need).
 *
 * <p>The snapshot is a value object — mutating the registry after the snapshot is taken does not
 * retroactively change the captured sets.
 *
 * @param noReturnCommandTypes immutable set of {@link TypeReference}s currently bound to a
 *                             fire-and-forget {@link DefaultCommandHandlerExecutor}.
 * @param returnCommandTypes   immutable set of {@link TypeReference}s currently bound to a
 *                             value-producing {@link DefaultCommandHandlerExecutor}.
 */
public record CommandRegistrationSnapshot(
                                          Set<TypeReference<?>> noReturnCommandTypes, Set<TypeReference<?>> returnCommandTypes) {

    /**
     * Creates an immutable registration snapshot.
     *
     * @throws NullPointerException if either set is null or contains null elements
     */
    public CommandRegistrationSnapshot {
        noReturnCommandTypes = Set.copyOf(noReturnCommandTypes);
        returnCommandTypes   = Set.copyOf(returnCommandTypes);
    }

    /** Total number of distinct command-type registrations across both sides. */
    public int size() {
        return noReturnCommandTypes.size() + returnCommandTypes.size();
    }

    /** {@code true} iff no command handler is currently registered. */
    public boolean isEmpty() {
        return noReturnCommandTypes.isEmpty() && returnCommandTypes.isEmpty();
    }
}
