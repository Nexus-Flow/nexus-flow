package net.nexus_flow.core.cqrs.query;

import java.util.Objects;
import java.util.function.Function;
import net.nexus_flow.core.types.TypeReference;

/**
 * Ergonomic DSL for assembling inline {@link QueryHandler} instances.
 *
 * <p>
 *
 * {@snippet :
 * record GetProductById(String id) {
 * }
 * record Product(String id, String name) {
 * }
 *
 * java.util.function.Function<GetProductById, Product> repo =
 *         query -> new Product(query.id(), "Keyboard");
 *
 * var handler = QueryHandlerDsl.forQuery(GetProductById.class)
 *         .returns(Product.class)
 *         .handle(repo::apply);
 * queryBus.register(handler);
 * }
 *
 * <p>The two {@link Class} parameters are <em>type witnesses</em>: they narrow the lambda parameter
 * and response types at the call site while providing the runtime routing keys used by {@link
 * QueryBus#register(AbstractQueryHandler)}.
 *
 * <p>For parameterised response types use the {@link QueryStep#returns(TypeReference)} overload.
 */
public final class QueryHandlerDsl {

    private QueryHandlerDsl() {
        throw new AssertionError("No instances of QueryHandlerDsl");
    }

    /**
     * Creates the first DSL step for an inline query handler.
     *
     * @param <T>       query payload type
     * @param queryType concrete query class used as the routing key
     * @return first DSL step for selecting the response type
     */
    static <T extends Record> QueryStep<T> forQuery(Class<T> queryType) {
        Objects.requireNonNull(queryType, "queryType");
        return new QueryStepImpl<>(new TypeReference<>(queryType));
    }

    /**
     * First DSL step that selects the response type.
     *
     * @param <T> query payload type
     */
    public sealed interface QueryStep<T extends Record> permits QueryStepImpl {

        /**
         * Selects a concrete, non-parameterised response type.
         *
         * @param <R>          response type
         * @param responseType response class token
         * @return next DSL step for supplying the handler function
         */
        <R> ResponseStep<T, R> returns(Class<R> responseType);

        /**
         * Selects a parameterised or otherwise non-reifiable response type.
         *
         * @param <R>          response type
         * @param responseType response type token
         * @return next DSL step for supplying the handler function
         */
        <R> ResponseStep<T, R> returns(TypeReference<R> responseType);
    }

    /**
     * Terminal DSL step that supplies the executable handler function.
     *
     * @param <T> query payload type
     * @param <R> response type
     */
    public sealed interface ResponseStep<T extends Record, R> permits ResponseStepImpl {

        /**
         * Builds an {@link AbstractQueryHandler} from the supplied function.
         *
         * @param handler function that computes the query result
         * @return concrete query handler ready for registration
         */
        AbstractQueryHandler<T, R> handle(Function<T, R> handler);
    }

    private record QueryStepImpl<T extends Record>(TypeReference<T> queryType)
            implements QueryStep<T> {

        /** {@inheritDoc} */
        @Override
        public <R> ResponseStep<T, R> returns(Class<R> responseType) {
            Objects.requireNonNull(responseType, "responseType");
            return new ResponseStepImpl<>(queryType, new TypeReference<>(responseType));
        }

        /** {@inheritDoc} */
        @Override
        public <R> ResponseStep<T, R> returns(TypeReference<R> responseType) {
            Objects.requireNonNull(responseType, "responseType");
            return new ResponseStepImpl<>(queryType, responseType);
        }
    }

    private record ResponseStepImpl<T extends Record, R>(
                                                         TypeReference<T> queryType, TypeReference<R> returnType) implements
            ResponseStep<T, R> {

        /** {@inheritDoc} */
        @Override
        public AbstractQueryHandler<T, R> handle(Function<T, R> handler) {
            Objects.requireNonNull(handler, "handler");
            return new InlineQueryHandler<>(queryType, returnType, handler, QuerySettings.NONE);
        }
    }

    /**
     * Concrete handler produced by the DSL.
     *
     * @param <T> query payload type
     * @param <R> response type
     */
    static final class InlineQueryHandler<T extends Record, R> extends AbstractQueryHandler<T, R> {

        private final Function<T, R> handler;
        private final QuerySettings  settings;

        /**
         * Creates an inline handler backed by a lambda.
         *
         * @param queryType  query routing key
         * @param returnType response type token
         * @param handler    executable handler function
         * @param settings   execution settings
         */
        InlineQueryHandler(
                TypeReference<T> queryType,
                TypeReference<R> returnType,
                Function<T, R> handler,
                QuerySettings settings) {
            super(queryType, returnType);
            this.handler  = Objects.requireNonNull(handler, "handler");
            this.settings = Objects.requireNonNull(settings, "settings");
        }

        /**
         * Executes the inline handler function.
         *
         * @param query query payload
         * @return handler result
         */
        @Override
        public R handle(T query) {
            return handler.apply(query);
        }

        /**
         * Returns execution settings for the inline handler.
         *
         * @return handler settings
         */
        @Override
        public QuerySettings settings() {
            return settings;
        }
    }
}
