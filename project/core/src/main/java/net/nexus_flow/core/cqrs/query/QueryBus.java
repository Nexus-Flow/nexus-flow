package net.nexus_flow.core.cqrs.query;

import net.nexus_flow.core.runtime.PerRuntime;

/**
 * Per-runtime query bus.
 *
 * <p>Instances are owned by {@link net.nexus_flow.core.runtime.FlowRuntime}; obtain one via {@code
 * runtime.queries()}. There is no process-wide accessor: see {@link PerRuntime} for the
 * architectural invariant pinned by {@code NoStaticGetInstanceTest}.
 *
 * <p>Routing is based on the exact runtime class of {@link Query#getBody()}; superclass and
 * interface hierarchy matches are not considered. The interface is intentionally open so Spring,
 * Quarkus, reactive, or persistence-backed adapters can provide alternative implementations.
 */
@PerRuntime
public interface QueryBus {

    /**
     * Builds a fresh, runtime-scoped {@link QueryBus}. Internal API: called from {@code
     * DefaultFlowRuntime}.
     *
     * <p>The default bus owns a private {@code HandlerRegistry&lt;Record, Object&gt;} directly.
     * Handler registration is registry-backed, providing lock-free dispatch plan lookup and stable
     * ordering guarantees.
     *
     * @return default in-memory query bus implementation
     */
    static QueryBus newInstance() {
        return new DefaultQueryBus();
    }

    /**
     * Registers a query handler for the specified query type.
     *
     * <p>Only one handler per exact query body type is allowed. Attempting to register a second
     * handler for the same type is silently ignored.
     *
     * @param <T>     the query payload type (must be a {@link Record})
     * @param <R>     the response type
     * @param handler the handler to register
     */
    <T extends Record, R> void register(AbstractQueryHandler<T, R> handler);

    /**
     * Unregisters a query handler.
     *
     * @param <T>     the query payload type (must be a {@link Record})
     * @param <R>     the response type
     * @param handler the handler to unregister
     */
    <T extends Record, R> void unregister(AbstractQueryHandler<T, R> handler);

    /**
     * Executes a query synchronously and returns the result.
     *
     * <p>Blocks until the handler completes. {@link QuerySettings} timeouts apply, and the default
     * implementation also shortens that timeout to honor any active {@link
     * net.nexus_flow.core.runtime.ExecutionContext#deadline()} bound through {@link
     * net.nexus_flow.core.runtime.FlowScope}.
     *
     * <p>The response type is a compile-time contract only; the query envelope does not carry a
     * result-type token. Reflection-based integrations that need runtime metadata should inspect the
     * corresponding {@link net.nexus_flow.core.cqrs.introspection.QueryHandlerRegistration}.
     *
     * @param <T>   the query payload type (must be a {@link Record})
     * @param <R>   the response type
     * @param query the query to execute
     * @return the handler's response
     * @throws net.nexus_flow.core.cqrs.query.exceptions.QueryNotRegisteredError    if no handler is
     *                                                                              registered for this exact query type
     * @throws net.nexus_flow.core.cqrs.query.exceptions.QueryHandlerExecutionError if the handler
     *                                                                              throws an exception or exceeds its effective timeout
     */
    <T extends Record, R> R ask(Query<T> query);

    /**
     * Returns a diagnostic snapshot of registered query handlers.
     *
     * <p>Mirrors the shape of {@link
     * net.nexus_flow.core.cqrs.command.CommandBus#registrationSnapshot()
     * CommandBus.registrationSnapshot()} so observability tooling sees a unified read-only view
     * across all dispatch targets.
     *
     * @return immutable snapshot of currently registered query types
     */
    QueryRegistrationSnapshot registrationSnapshot();
}
