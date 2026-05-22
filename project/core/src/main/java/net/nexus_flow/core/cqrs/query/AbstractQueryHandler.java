package net.nexus_flow.core.cqrs.query;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import net.nexus_flow.core.cqrs.introspection.QueryHandlerRegistration;
import net.nexus_flow.core.types.TypeReference;

/**
 * Canonical base class for query handlers.
 *
 * <p>The class remains open for framework adapters and application code that prefer named handler
 * types over the inline DSL. It also exposes reflective factory methods used by Spring, Quarkus,
 * and similar integration modules.
 *
 * @param <T> query payload type
 * @param <R> response type
 */
public abstract class AbstractQueryHandler<T extends Record, R> extends QueryTypeSignature<T, R>
        implements QueryHandler<T, R> {

    private final BiFunction<AbstractQueryHandler<T, R>, T, R> queryHandlerRef =
            AbstractQueryHandler::handle;

    /**
     * Creates an anonymous-subclass handler and resolves {@code <T, R>} from bytecode.
     *
     * @throws IllegalStateException if the subclass does not preserve concrete generic type arguments
     */
    protected AbstractQueryHandler() {
        super();
    }

    /**
     * Creates a handler from pre-resolved type tokens.
     *
     * @param queryType  concrete query body type token
     * @param returnType concrete response type token
     */
    AbstractQueryHandler(TypeReference<T> queryType, TypeReference<R> returnType) {
        super(queryType, returnType);
    }

    /**
     * Returns the preferred DSL entry point for assembling an inline query handler.
     *
     * <p>
     *
     * {@snippet :
     * var handler =
     *         AbstractQueryHandler.forQuery(GetProductById.class)
     *                 .returns(Product.class)
     *                 .handle(q -> repo.findById(q.id()));
     * }
     *
     * @param <T>       query payload type
     * @param queryType concrete query class used as the routing key
     * @return first DSL step for selecting the response type
     */
    public static <T extends Record> QueryHandlerDsl.QueryStep<T> forQuery(Class<T> queryType) {
        return QueryHandlerDsl.forQuery(queryType);
    }

    /**
     * Builds a query handler from an already-discovered Java method.
     *
     * <p>IoC integrations can scan an application bean method such as {@code Product byId(GetProduct
     * query)} and use the returned registration token to inspect routing metadata and register on a
     * {@link QueryBus}.
     *
     * @param target bean or object that owns {@code method}
     * @param method method with exactly one record parameter and a non-{@code void} return type
     * @return opaque registration token for the discovered handler
     * @throws IllegalArgumentException if {@code method} is not a valid query-handler method
     */
    public static QueryHandlerRegistration fromMethod(Object target, Method method) {
        return fromMethod(target, method, QuerySettings.NONE);
    }

    /**
     * Builds a method-backed query handler with explicit settings.
     *
     * @param target   bean or object that owns {@code method}
     * @param method   method with exactly one record parameter and a non-{@code void} return type
     * @param settings execution settings to apply to the resulting handler
     * @return opaque registration token for the discovered handler
     * @throws IllegalArgumentException if {@code method} is not a valid query-handler method
     * @throws NullPointerException     if {@code settings} is {@code null}
     */
    public static QueryHandlerRegistration fromMethod(
            Object target, Method method, QuerySettings settings) {
        Objects.requireNonNull(settings, "settings");
        return QueryHandlerMethodAdapter.fromMethod(target, method, settings);
    }

    /**
     * Returns per-handler execution constraints such as timeouts or concurrency limits.
     *
     * <p>
     *
     * {@snippet :
     * &#64;Override
     * public QuerySettings settings() {
     *     return QuerySettings.withTimeout(Duration.ofSeconds(2));
     * }
     * }
     *
     * @return handler settings, or {@link QuerySettings#NONE} when using defaults
     */
    @Override
    public QuerySettings settings() {
        return QuerySettings.NONE;
    }

    Callable<R> getInnerHandler(T query) {
        return () -> queryHandlerRef.apply(this, query);
    }
}
