package net.nexus_flow.core.runtime.registry;

import static org.junit.jupiter.api.Assertions.*;

import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * {@link HandlerRegistry#planFor(Class)} is backed by a {@link ClassValue}; once a plan is computed
 * for a class it is memoised by reference for the lifetime of the registration set.
 */
class HandlerRegistryClassValueCacheTest {

    static final class Msg {
    }

    @Test
    void planFor_returnsTheSameInstance_underHeavyLookup_aftherFirstFetch() {
        HandlerRegistry<Object, Object> reg = new HandlerRegistry<>();
        reg.registerInvoker(Msg.class, (m, ctx) -> null, /* order= */ 0);

        DispatchPlan<Object, Object> first = reg.planFor(Msg.class);
        for (int i = 0; i < 1_000_000; i++) {
            assertSame(first, reg.planFor(Msg.class), "planFor must memoise via ClassValue");
        }
        assertTrue(first.size() == 1, "plan should contain the single registered invoker");
    }

    @Test
    void planFor_unknownType_returnsAStableEmptyPlanByReference() {
        HandlerRegistry<Object, Object> reg = new HandlerRegistry<>();
        DispatchPlan<Object, Object>    a   = reg.planFor(Msg.class);
        DispatchPlan<Object, Object>    b   = reg.planFor(Msg.class);
        assertSame(a, b);
        assertTrue(a.isEmpty());
    }

    @Test
    void unregister_forcesRebuild_newPlanIdentity() {
        HandlerRegistry<Object, Object> reg = new HandlerRegistry<>();
        reg.registerInvoker(Msg.class, (m, ctx) -> null, /* order= */ 0);

        DispatchPlan<Object, Object> before = reg.planFor(Msg.class);
        reg.unregister(Msg.class);
        DispatchPlan<Object, Object> after = reg.planFor(Msg.class);

        assertNotSame(before, after, "unregister must invalidate the ClassValue entry");
        assertTrue(after.isEmpty(), "post-unregister plan must be empty");
    }

    @Test
    void register_afterFirstLookup_forcesRebuild_newPlanIdentity() throws Throwable {
        HandlerRegistry<Object, Object> reg = new HandlerRegistry<>();

        DispatchPlan<Object, Object> empty = reg.planFor(Msg.class);
        assertTrue(empty.isEmpty());

        HandlerInvoker<Object, Object> invoker = (m, ctx) -> "ok";
        reg.registerInvoker(Msg.class, invoker, /* order= */ 0);

        DispatchPlan<Object, Object> next = reg.planFor(Msg.class);
        assertNotSame(empty, next, "register must invalidate the prior plan");
        assertSame(invoker, next.handlers().getFirst());
        assertSame("ok", next.handlers().getFirst().invoke(new Msg(), (ExecutionContext) null));
    }

    @Test
    void clear_dropsEverything_andReturnsEmptyPlans() {
        HandlerRegistry<Object, Object> reg = new HandlerRegistry<>();
        reg.registerInvoker(Msg.class, (m, ctx) -> null, /* order= */ 0);
        reg.clear();
        assertTrue(reg.planFor(Msg.class).isEmpty());
    }
}
