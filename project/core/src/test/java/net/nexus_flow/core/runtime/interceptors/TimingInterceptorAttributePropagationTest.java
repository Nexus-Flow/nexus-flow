package net.nexus_flow.core.runtime.interceptors;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.dispatch.InvocationContext;
import net.nexus_flow.core.runtime.dispatch.InvocationKind;
import net.nexus_flow.core.runtime.dispatch.SyncDispatcher;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.junit.jupiter.api.Test;

/**
 * pins the contract of {@link TimingDispatchInterceptor}:
 *
 * <ul>
 * <li>After a successful dispatch the {@link TimingDispatchInterceptor#DURATION_MS_KEY} attribute
 * is present in the {@link InvocationContext#attributes()} bag of <em>this</em> dispatch.
 * <li><strong>Fan-out invariant</strong> — the attribute does NOT leak into a sibling {@link
 * InvocationContext}. In a fan-out each sibling gets its own bag, so a non-instrumented
 * sibling observes no timing data even when its parent ran one.
 * </ul>
 */
class TimingInterceptorAttributePropagationTest {

    @Test
    void durationMs_isPublishedToInvocationAttributes_afterDispatch() {
        InvocationContext invCtx =
                InvocationContext.of(
                                     InvocationKind.COMMAND, "p", ExecutionContext.root(), ErrorPolicy.failFast());

        DispatchResult<String> r =
                SyncDispatcher.dispatchThrough(
                                               invCtx,
                                               List.of(new TimingDispatchInterceptor()),
                                               () -> {
                                                   // Take *some* observable wall-clock time so durationMs
                                                   // is non-negative (it is allowed to round down to 0
                                                   // on very fast machines — we only assert presence).
                                                   return DispatchResult.success("ok");
                                               });

        assertInstanceOf(DispatchResult.Success.class, r);
        Object stamped = invCtx.attributes().get(TimingDispatchInterceptor.DURATION_MS_KEY);
        assertInstanceOf(
                         Long.class, stamped, "Timing interceptor must publish dispatch.durationMs as a Long");
        assertTrue((Long) stamped >= 0, "durationMs must be non-negative");
    }

    @Test
    void durationMs_doesNotLeakToSiblingInvocationContexts_inFanOut() {
        // Two sibling dispatches running the same handler with the same
        // ExecutionContext root but DISTINCT InvocationContexts (as the
        // runtime materialises one per dispatch). The timing attribute
        // written on dispatch A's bag must not be visible to dispatch B.
        InvocationContext a =
                InvocationContext.of(
                                     InvocationKind.COMMAND, "a", ExecutionContext.root(), ErrorPolicy.failFast());
        InvocationContext b =
                InvocationContext.of(
                                     InvocationKind.COMMAND, "b", ExecutionContext.root(), ErrorPolicy.failFast());

        SyncDispatcher.dispatchThrough(
                                       a, List.of(new TimingDispatchInterceptor()), () -> DispatchResult.success("ok-a"));

        // Sibling dispatch B runs WITHOUT the timing interceptor at all;
        // it must observe no leakage from the bag mutated by A.
        SyncDispatcher.dispatchThrough(b, List.of(), () -> DispatchResult.success("ok-b"));

        assertTrue(
                   a.attributes().containsKey(TimingDispatchInterceptor.DURATION_MS_KEY),
                   "dispatch A must carry its own duration");
        assertFalse(
                    b.attributes().containsKey(TimingDispatchInterceptor.DURATION_MS_KEY),
                    "dispatch B must NOT inherit dispatch.durationMs from sibling A");
    }
}
