package net.nexus_flow.core.cqrs.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.time.Duration;
import net.nexus_flow.core.cqrs.introspection.QueryHandlerRegistration;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link AbstractQueryHandler#fromMethod(Object, java.lang.reflect.Method,
 * QuerySettings)} adapter that wraps bean methods as query handlers.
 */
class QueryHandlerMethodAdapterTest {

    record GetOrder(String id) {
    }

    record OrderView(String id, String label) {
    }

    static final class Bean {
        OrderView get(GetOrder query) {
            return new OrderView(query.id(), "order:" + query.id());
        }

        void invalid(GetOrder query) {
        }
    }

    /** fromMethod creates a handler from a bean method with custom query settings. */
    @Test
    void fromMethod_registersBeanMethodWithQuerySettings() throws NoSuchMethodException {
        Bean                     bean         = new Bean();
        Method                   method       = Bean.class.getDeclaredMethod("get", GetOrder.class);
        QueryHandlerRegistration registration =
                AbstractQueryHandler.fromMethod(
                                                bean, method, QuerySettings.withTimeout(Duration.ofSeconds(1)));

        assertEquals(GetOrder.class, registration.queryType().getType());
        assertEquals(OrderView.class, registration.returnType().getType());
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            registration.registerOn(runtime.queries());
            OrderView view = runtime.queries().ask(Query.builder().body(new GetOrder("O-4")).build());
            assertEquals("O-4", view.id());
            assertEquals("order:O-4", view.label());
        }
    }

    /** fromMethod rejects void return type methods. */
    @Test
    void fromMethod_rejectsVoidQueryMethods() throws NoSuchMethodException {
        Method method = Bean.class.getDeclaredMethod("invalid", GetOrder.class);

        assertThrows(
                     IllegalArgumentException.class, () -> AbstractQueryHandler.fromMethod(new Bean(), method));
    }
}
