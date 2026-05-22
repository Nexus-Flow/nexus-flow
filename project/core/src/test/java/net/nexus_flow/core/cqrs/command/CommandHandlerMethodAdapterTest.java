package net.nexus_flow.core.cqrs.command;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.introspection.CommandHandlerRegistration;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

class CommandHandlerMethodAdapterTest {

    record PlaceOrder(String id) {
    }

    static final class Bean {
        private final AtomicReference<String> placed = new AtomicReference<>();

        void place(PlaceOrder command) {
            placed.set(command.id());
        }

        String describe(PlaceOrder command) {
            return "order:" + command.id();
        }
    }

    @Test
    void fromMethod_returnsRegistrationForVoidBeanMethod() throws NoSuchMethodException {
        Bean   bean   = new Bean();
        Method method = Bean.class.getDeclaredMethod("place", PlaceOrder.class);

        CommandHandlerRegistration registration = CommandHandler.fromMethod(bean, method);

        assertEquals(PlaceOrder.class, registration.commandType().getType());
        assertFalse(registration.returnsValue());
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            registration.registerOn(runtime.commands());
            runtime
                    .commands()
                    .dispatch(Command.<PlaceOrder>builder().body(new PlaceOrder("O-1")).build());
            assertEquals("O-1", bean.placed.get());
        }
    }

    @Test
    void fromMethod_returnsRegistrationForValueReturningBeanMethod() throws NoSuchMethodException {
        Bean   bean   = new Bean();
        Method method = Bean.class.getDeclaredMethod("describe", PlaceOrder.class);

        CommandHandlerRegistration registration =
                CommandHandler.fromMethod(
                                          bean,
                                          method,
                                          new CommandHandlerOptions(
                                                  5, 0, InitializationType.LAZY, false, new CommandSettings(), null));

        assertEquals(PlaceOrder.class, registration.commandType().getType());
        assertTrue(registration.returnsValue());
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            registration.registerOn(runtime.commands());
            String response =
                    runtime
                            .commands()
                            .dispatchAndReturn(Command.<PlaceOrder>builder().body(new PlaceOrder("O-2")).build());
            assertEquals("order:O-2", response);
        }
    }
}
