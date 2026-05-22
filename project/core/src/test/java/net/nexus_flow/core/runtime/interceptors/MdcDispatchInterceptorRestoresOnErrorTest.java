package net.nexus_flow.core.runtime.interceptors;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.dispatch.InvocationContext;
import net.nexus_flow.core.runtime.dispatch.InvocationKind;
import net.nexus_flow.core.runtime.dispatch.SyncDispatcher;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowError;
import org.junit.jupiter.api.Test;

/**
 * pins the "restore-on-error" contract of {@link MdcDispatchInterceptor}.
 *
 * <p>The MDC snapshot captured on entry MUST be restored when the inner chain throws — even when
 * the throw escapes as a wrapped Technical. The dispatch otherwise leaves a polluted MDC behind
 * that would leak into the next dispatch reusing the same carrier thread (the VT pool recycles
 * threads liberally).
 */
class MdcDispatchInterceptorRestoresOnErrorTest {

    @Test
    void mdc_isRestored_whenChainThrows_evenFromAnEmptyBaseline() {
        // Baseline: MDC starts empty.
        assertTrue(
                   MdcDispatchInterceptor.current().isEmpty(),
                   "test precondition: MDC must start clean on the test thread");

        InvocationContext invCtx =
                InvocationContext.of(
                                     InvocationKind.COMMAND, "p", ExecutionContext.root(), ErrorPolicy.failFast());

        DispatchResult<String> r =
                SyncDispatcher.dispatchThrough(
                                               invCtx,
                                               List.of(new MdcDispatchInterceptor()),
                                               () -> {
                                                   // While the handler runs, the MDC is populated.
                                                   Map<String, String> live = MdcDispatchInterceptor.current();
                                                   assertTrue(live.containsKey(MdcDispatchInterceptor.TRACE_ID_KEY));
                                                   assertTrue(live.containsKey(MdcDispatchInterceptor.CORRELATION_ID_KEY));
                                                   assertTrue(live.containsKey(MdcDispatchInterceptor.CAUSATION_ID_KEY));
                                                   // Now blow up so the restore path is exercised.
                                                   throw new IllegalStateException("boom");
                                               });

        if (!(r instanceof DispatchResult.Failure<String> f)) {
            fail("expected Failure, got " + r);
            return;
        }
        // Cause is wrapped in Technical (handler throw).
        assertInstanceOf(FlowError.Technical.class, f.cause());

        // Restore-on-error invariant: the MDC is empty again.
        assertTrue(
                   MdcDispatchInterceptor.current().isEmpty(),
                   "MDC must be restored to its baseline after a failing dispatch. "
                           + "Observed leftover: "
                           + MdcDispatchInterceptor.current());
    }

    @Test
    void mdc_isRestored_whenChainSucceeds() {
        InvocationContext invCtx =
                InvocationContext.of(
                                     InvocationKind.COMMAND, "p", ExecutionContext.root(), ErrorPolicy.failFast());

        DispatchResult<String> r =
                SyncDispatcher.dispatchThrough(
                                               invCtx, List.of(new MdcDispatchInterceptor()), () -> DispatchResult.success("ok"));

        assertEquals("ok", ((DispatchResult.Success<String>) r).value());
        assertTrue(
                   MdcDispatchInterceptor.current().isEmpty(),
                   "MDC must be empty after a successful dispatch");
    }
}
