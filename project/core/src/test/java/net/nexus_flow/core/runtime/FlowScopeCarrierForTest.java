package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import org.junit.jupiter.api.Test;

/**
 * Pins the contract of {@link FlowScope#carrierFor(ExecutionContext)}:
 *
 * <ul>
 * <li>The returned {@link ScopedValue.Carrier} binds the supplied {@link ExecutionContext} as
 * {@link FlowScope#CURRENT_CONTEXT} when {@code carrier.call(...)} or {@code
 *       carrier.run(...)} is invoked.
 * <li>The same carrier instance can be reused across many calls — every binding observes the same
 * context. This is the optimisation that lets {@link net.nexus_flow.core.saga.SagaRunner
 * SagaRunner} hand one carrier through a long loop without re-allocating per envelope (the
 * §11 audit O2 observation).
 * <li>Passing a {@code null} context raises {@link NullPointerException}.
 * <li>Outside the carrier's dynamic extent, {@link FlowScope#current()} returns {@code empty} (no
 * leak across boundaries).
 * </ul>
 *
 * <p>Note: this test does NOT assert that the carrier object is allocated only once across many
 * binds — that is the optimisation's benefit but it's an internal observation; what matters for
 * correctness is that the binding semantics are stable when callers reuse it.
 */
class FlowScopeCarrierForTest {

    private static ExecutionContext freshContext() {
        return new ExecutionContext(
                MessageId.random(),
                TraceId.random(),
                CorrelationId.random(),
                CausationId.ROOT,
                null,
                null,
                null,
                CancellationToken.create(),
                java.util.Map.of());
    }

    @Test
    void carrierFor_call_bindsContextForDurationOfBody() {
        ExecutionContext    ctx     = freshContext();
        ScopedValue.Carrier carrier = FlowScope.carrierFor(ctx);
        assertNotNull(carrier);

        ExecutionContext observed = carrier.call(FlowScope::requireCurrent);
        assertSame(ctx, observed, "body must observe the bound context");
    }

    @Test
    void carrierFor_outsideDynamicExtent_currentIsEmpty() {
        ExecutionContext ctx = freshContext();
        FlowScope.carrierFor(ctx); // construct but don't enter
        assertFalse(
                    FlowScope.current().isPresent(), "merely constructing a carrier must NOT bind the context");
    }

    @Test
    void carrierFor_reuse_acrossManyCalls_bindsSameContextEachTime() {
        ExecutionContext    ctx     = freshContext();
        ScopedValue.Carrier carrier = FlowScope.carrierFor(ctx);

        int                    iterations = 1000;
        List<ExecutionContext> seen       = new ArrayList<>(iterations);
        for (int i = 0; i < iterations; i++) {
            seen.add(carrier.call(FlowScope::requireCurrent));
        }

        // Every iteration must observe the EXACT same instance — the cached carrier's binding
        // is structural, not "the same field value by coincidence".
        for (ExecutionContext observed : seen) {
            assertSame(ctx, observed);
        }
        assertEquals(iterations, seen.size());
    }

    @Test
    void carrierFor_nullContext_throws() {
        assertThrows(NullPointerException.class, () -> FlowScope.carrierFor(null));
    }

    @Test
    void carrierFor_distinctContexts_bindIndependently() {
        ExecutionContext    ctxA     = freshContext();
        ExecutionContext    ctxB     = freshContext();
        ScopedValue.Carrier carrierA = FlowScope.carrierFor(ctxA);
        ScopedValue.Carrier carrierB = FlowScope.carrierFor(ctxB);

        ExecutionContext observedA = carrierA.call(FlowScope::requireCurrent);
        ExecutionContext observedB = carrierB.call(FlowScope::requireCurrent);

        assertSame(ctxA, observedA);
        assertSame(ctxB, observedB);
    }
}
