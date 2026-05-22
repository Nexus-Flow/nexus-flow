package net.nexus_flow.core.cqrs.query;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.types.TypeReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Validates the ergonomic {@code AbstractQueryHandler.forQuery(Class)} DSL. */
class QueryHandlerDslTest {

    record GetProduct(String id) {
    }

    record Product(String id, String name) {
    }

    record ListProducts(String category) {
    }

    @Nested
    @DisplayName("forQuery(Class).returns(Class) — basic flow")
    class BasicFlow {

        /** DSL-produced handler routes queries end-to-end with proper type bindings. */
        @Test
        void handle_buildsTypedHandler_thatRoutesEndToEnd() {
            try (FlowRuntime runtime = FlowRuntime.builder().build()) {
                var handler =
                        AbstractQueryHandler.forQuery(GetProduct.class)
                                .returns(Product.class)
                                .handle(q -> new Product(q.id(), "Widget-" + q.id()));

                assertInstanceOf(
                                 AbstractQueryHandler.class,
                                 handler,
                                 "DSL-produced handler must be an AbstractQueryHandler");
                assertEquals(
                             GetProduct.class,
                             ((AbstractQueryHandler<?, ?>) handler).getQueryType().getType(),
                             "Query routing key must be GetProduct");

                runtime.queries().register(handler);
                try {
                    Product p = runtime.queries().ask(Query.builder().body(new GetProduct("P7")).build());
                    assertEquals("P7", p.id());
                    assertEquals("Widget-P7", p.name());
                } finally {
                    runtime.queries().unregister(handler);
                }
            }
        }

        /** TypeReference handles parameterized response types (e.g., List&lt;T&gt;). */
        @Test
        void returns_acceptsTypeReference_forParameterisedResponses() {
            var handler =
                    AbstractQueryHandler.forQuery(ListProducts.class)
                            .returns(new TypeReference<List<Product>>() {
                            })
                            .handle(
                                    q -> List.of(
                                                 new Product("1", q.category() + "-alpha"),
                                                 new Product("2", q.category() + "-beta")));

            assertInstanceOf(AbstractQueryHandler.class, handler);
            assertEquals(
                         ListProducts.class, ((AbstractQueryHandler<?, ?>) handler).getQueryType().getType());

            QueryHandlerDsl.InlineQueryHandler<ListProducts, List<Product>> inline =
                    (QueryHandlerDsl.InlineQueryHandler<ListProducts, List<Product>>) handler;
            List<Product>                                                   result = inline.handle(new ListProducts("tools"));
            assertEquals(2, result.size());
            assertEquals("tools-alpha", result.getFirst().name());
        }
    }

    @Nested
    @DisplayName("Null guards")
    class NullGuards {

        /** forQuery rejects null query class. */
        @Test
        void forQuery_rejectsNullClass() {
            assertThrows(NullPointerException.class, () -> AbstractQueryHandler.forQuery(null));
        }

        /** returns rejects null response class. */
        @Test
        void returns_rejectsNullClass() {
            assertThrows(
                         NullPointerException.class,
                         () -> AbstractQueryHandler.forQuery(GetProduct.class).returns((Class<?>) null));
        }

        /** returns rejects null TypeReference. */
        @Test
        void returns_rejectsNullTypeReference() {
            assertThrows(
                         NullPointerException.class,
                         () -> AbstractQueryHandler.forQuery(GetProduct.class).returns((TypeReference<?>) null));
        }

        /** handle rejects null function. */
        @Test
        void handle_rejectsNullFunction() {
            assertThrows(
                         NullPointerException.class,
                         () -> AbstractQueryHandler.forQuery(GetProduct.class).returns(Product.class).handle(null));
        }
    }

    @Nested
    @DisplayName("Sealed DSL contract")
    class SealedSurface {

        /** QueryStep interface is sealed to restrict implementations. */
        @Test
        void queryStep_isSealed() {
            assertTrue(QueryHandlerDsl.QueryStep.class.isSealed(), "QueryStep must be sealed");
        }

        /** ResponseStep interface is sealed to restrict implementations. */
        @Test
        void responseStep_isSealed() {
            assertTrue(QueryHandlerDsl.ResponseStep.class.isSealed(), "ResponseStep must be sealed");
        }

        /** DSL utility class is not instantiable. */
        @Test
        void dsl_isNotInstantiable() {
            assertThrows(
                         Exception.class,
                         () -> {
                             var ctor = QueryHandlerDsl.class.getDeclaredConstructor();
                             ctor.setAccessible(true);
                             ctor.newInstance();
                         });
        }
    }
}
