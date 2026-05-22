package net.nexus_flow.core.cqrs.introspection;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.lang.reflect.Method;
import net.nexus_flow.core.cqrs.command.CommandHandler;
import net.nexus_flow.core.cqrs.event.DomainEventListener;
import net.nexus_flow.core.cqrs.query.AbstractQueryHandler;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import org.junit.jupiter.api.Test;

/**
 * Tests for handler method introspection. Verifies that the {@code HandlerMethodIntrospector} can
 * correctly extract message types, return types, and registration tokens from handler methods for
 * commands, queries, and domain events. Also validates that invalid method signatures (e.g.,
 * non-record parameters) are rejected with appropriate errors.
 */
class HandlerMethodIntrospectorTest {

    record PlaceOrder(String id) {
    }

    static final class OrderPlaced extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        OrderPlaced(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Bean {
        void command(PlaceOrder command) {
        }

        String query(PlaceOrder command) {
            return command.id();
        }

        void event(OrderPlaced event) {
        }

        void invalid(String value) {
        }
    }

    @Test
    void recordMessageTypeExtractsCommandOrQueryParameter() throws NoSuchMethodException {
        // Method introspection must extract record message types from handler method parameters.
        Method method = Bean.class.getDeclaredMethod("command", PlaceOrder.class);

        assertEquals(PlaceOrder.class, HandlerMethodIntrospector.recordMessageType(method).getType());
        assertEquals(PlaceOrder.class, HandlerMethodIntrospector.recordMessageClass(method));
        assertEquals(Void.class, HandlerMethodIntrospector.returnType(method).getType());
        assertTrue(HandlerMethodIntrospector.isVoidReturn(method));
    }

    @Test
    void eventMessageTypeExtractsDomainEventParameter() throws NoSuchMethodException {
        // Method introspection must extract domain event types from handler method parameters.
        Method method = Bean.class.getDeclaredMethod("event", OrderPlaced.class);

        assertEquals(OrderPlaced.class, HandlerMethodIntrospector.eventMessageType(method).getType());
        assertEquals(OrderPlaced.class, HandlerMethodIntrospector.eventMessageClass(method));
        assertEquals(OrderPlaced.class, HandlerMethodIntrospector.messageType(method).getType());
    }

    @Test
    void fromMethodFactoriesReturnOpaqueRegistrationTokens() throws NoSuchMethodException {
        // Method factories must return registration tokens that encapsulate handler type information.
        Bean   bean    = new Bean();
        Method command = Bean.class.getDeclaredMethod("command", PlaceOrder.class);
        Method query   = Bean.class.getDeclaredMethod("query", PlaceOrder.class);
        Method event   = Bean.class.getDeclaredMethod("event", OrderPlaced.class);

        CommandHandlerRegistration commandRegistration = CommandHandler.fromMethod(bean, command);
        QueryHandlerRegistration   queryRegistration   = AbstractQueryHandler.fromMethod(bean, query);
        EventListenerRegistration  eventRegistration   = DomainEventListener.fromMethod(bean, event);

        assertEquals(PlaceOrder.class, commandRegistration.commandType().getType());
        assertFalse(commandRegistration.returnsValue());
        assertEquals(PlaceOrder.class, queryRegistration.queryType().getType());
        assertEquals(String.class, queryRegistration.returnType().getType());
        assertEquals(OrderPlaced.class, eventRegistration.eventType().getType());
    }

    @Test
    void recordMessageTypeRejectsNonRecordParameters() throws NoSuchMethodException {
        // Method introspection must reject non-record message types with a clear error.
        Method method = Bean.class.getDeclaredMethod("invalid", String.class);

        IllegalArgumentException ex =
                assertThrows(
                             IllegalArgumentException.class,
                             () -> HandlerMethodIntrospector.recordMessageType(method));
        assertTrue(ex.getMessage().contains("Record"));
    }
}
