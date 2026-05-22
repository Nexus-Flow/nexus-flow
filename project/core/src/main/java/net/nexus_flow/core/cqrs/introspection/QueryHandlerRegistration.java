package net.nexus_flow.core.cqrs.introspection;

import java.util.Objects;
import net.nexus_flow.core.cqrs.query.AbstractQueryHandler;
import net.nexus_flow.core.cqrs.query.QueryBus;
import net.nexus_flow.core.types.TypeReference;

/**
 * Opaque registration token for query handlers discovered by IoC adapters.
 *
 * <p>Spring runtime reflection, Quarkus build-time indexing, Micronaut annotation processors, or
 * similar integrations can scan a bean method, call {@code AbstractQueryHandler.fromMethod(...)},
 * inspect the query and response type tokens, and register on a {@link QueryBus} without leaking an
 * {@code AbstractQueryHandler<?, ?>} reference through their APIs.
 */
public final class QueryHandlerRegistration {
    private final AbstractQueryHandler<?, ?> handler;
    private final TypeReference<?>           queryType;
    private final TypeReference<?>           returnType;

    QueryHandlerRegistration(
            AbstractQueryHandler<?, ?> handler, TypeReference<?> queryType, TypeReference<?> returnType) {
        this.handler    = Objects.requireNonNull(handler, "handler");
        this.queryType  = Objects.requireNonNull(queryType, "queryType");
        this.returnType = Objects.requireNonNull(returnType, "returnType");
    }

    /**
     * Creates a token for a query handler.
     *
     * @param <T>        query payload type
     * @param <R>        response type
     * @param handler    concrete query handler
     * @param queryType  exact query routing key
     * @param returnType response type token exposed for reflection-based integrations
     * @return immutable registration token
     */
    public static <T extends Record, R> QueryHandlerRegistration of(
            AbstractQueryHandler<T, R> handler, TypeReference<T> queryType, TypeReference<R> returnType) {
        return new QueryHandlerRegistration(handler, queryType, returnType);
    }

    /**
     * Returns the exact query type used as the query-bus routing key.
     *
     * @return query routing type token
     */
    @SuppressWarnings(
        "java:S1452") // Type token (akin to Class<?>); routing key, not a generic container.
    public TypeReference<?> queryType() {
        return queryType;
    }

    /**
     * Returns the response type produced by the query handler.
     *
     * @return response type token for reflective integrations
     */
    @SuppressWarnings(
        "java:S1452") // Type token (akin to Class<?>); identifies the response shape for the query
    // bus.
    public TypeReference<?> returnType() {
        return returnType;
    }

    /**
     * Registers the wrapped handler on the given query bus.
     *
     * @param bus target query bus
     * @throws NullPointerException if {@code bus} is {@code null}
     */
    public void registerOn(QueryBus bus) {
        Objects.requireNonNull(bus, "bus");
        registerTyped(bus, handler);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Record, R> void registerTyped(
            QueryBus bus, AbstractQueryHandler<?, ?> handler) {
        bus.register((AbstractQueryHandler<T, R>) handler);
    }
}
