package net.nexus_flow.core.runtime.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowError;
import org.junit.jupiter.api.Test;

/**
 * Error wrapping invariants for {@link DispatchInterceptor}.
 *
 * <p>Covers the three branches of interceptor error handling:
 *
 * <ol>
 * <li>An interceptor throws {@link FlowError.Domain} → propagated verbatim, no wrapping.
 * <li>An interceptor throws a non-{@code FlowError} {@link Throwable} → wrapped in {@link
 * FlowError.Technical} carrying the failing {@link InvocationContext} (stage = PRE / POST
 * attributable).
 * <li>An "evil" interceptor calling {@code chain.proceed()} and seeing a {@link
 * DispatchResult.Failure} cannot silently upgrade it to {@link DispatchResult.Success}.
 * </ol>
 *
 * <p>These tests bypass the bus layer and exercise {@link
 * SyncDispatcher#dispatchThrough(InvocationContext, List, java.util.concurrent.Callable)} directly
 * so the assertion is on the interceptor framework itself, not on the surrounding command path.
 */
class InterceptorErrorWrappingTest {

    /** Marker domain exception that opts into the no-wrap path. */
    static final class TenantNotFound extends RuntimeException implements FlowError.Domain {
        @Serial
        private static final long serialVersionUID = 1L;

        TenantNotFound(String m) {
            super(m);
        }
    }

    private static InvocationContext rootCtx() {
        return InvocationContext.of(
                                    InvocationKind.COMMAND, "payload", ExecutionContext.root(), ErrorPolicy.failFast());
    }

    // Domain error: propagated verbatim from an interceptor.
    @Test
    void interceptorThrowingDomainError_isReturnedVerbatim_noWrapping() {
        DispatchInterceptor evil =
                new DispatchInterceptor() {
                    @Override
                    public <R> DispatchResult<R> intercept(InvocationContext ctx, DispatchChain<R> chain) {
                        throw new TenantNotFound("tenant=42");
                    }
                };

        DispatchResult<String> r =
                SyncDispatcher.dispatchThrough(
                                               rootCtx(), List.of(evil), () -> DispatchResult.success("never-reached"));

        if (!(r instanceof DispatchResult.Failure<String> f)) {
            fail("expected Failure, got " + r);
            return;
        }
        assertInstanceOf(
                         TenantNotFound.class,
                         f.cause(),
                         "Domain errors raised inside an interceptor must propagate VERBATIM. Got: "
                                 + f.cause().getClass().getName());
        assertEquals("tenant=42", f.cause().getMessage());
    }

    // Non-domain error: wrapped in Technical with ctx + stage.
    @Test
    void interceptorThrowingPlainException_isWrappedInTechnical_withStagePre() {
        ExecutionContext  ec     = ExecutionContext.root();
        InvocationContext invCtx =
                InvocationContext.of(InvocationKind.QUERY, "q", ec, ErrorPolicy.failFast());

        DispatchInterceptor brittle =
                new DispatchInterceptor() {
                    @Override
                    public <R> DispatchResult<R> intercept(InvocationContext ctx, DispatchChain<R> chain) {
                        throw new IllegalStateException("DB down at PRE");
                    }
                };

        DispatchResult<String> r =
                SyncDispatcher.dispatchThrough(
                                               invCtx, List.of(brittle), () -> DispatchResult.success("ok"));

        if (!(r instanceof DispatchResult.Failure<String> f)) {
            fail("expected Failure, got " + r);
            return;
        }
        FlowError.Technical tech =
                assertInstanceOf(
                                 FlowError.Technical.class,
                                 f.cause(),
                                 "non-domain throw inside an interceptor must be wrapped in Technical");
        assertSame(
                   ec,
                   tech.executionContext(),
                   "Technical must carry the ExecutionContext of the failing dispatch");
        assertInstanceOf(IllegalStateException.class, tech.getCause());
        assertTrue(
                   tech.getMessage().contains("stage=PRE"),
                   "Technical message must attribute the failing stage. Got: " + tech.getMessage());
    }

    @Test
    void interceptorThrowingAfterProceed_isAttributedToPostStage() {
        DispatchInterceptor postThrower =
                new DispatchInterceptor() {
                    @Override
                    public <R> DispatchResult<R> intercept(InvocationContext ctx, DispatchChain<R> chain) {
                        DispatchResult<R> inner = chain.proceed();
                        // Force assignment so the compiler does not optimise the
                        // call away in a future JIT-level reshuffle.
                        if (inner == null) {
                            throw new AssertionError();
                        }
                        throw new RuntimeException("post-blowup");
                    }
                };

        DispatchResult<String> r =
                SyncDispatcher.dispatchThrough(
                                               rootCtx(), List.of(postThrower), () -> DispatchResult.success("ok"));

        if (!(r instanceof DispatchResult.Failure<String> f)) {
            fail("expected Failure, got " + r);
            return;
        }
        FlowError.Technical tech = assertInstanceOf(FlowError.Technical.class, f.cause());
        assertTrue(
                   tech.getMessage().contains("stage=POST"),
                   "throw after chain.proceed() must be attributed to POST. Got: " + tech.getMessage());
    }

    // "Evil" interceptor cannot upgrade Failure → Success.
    @Test
    void interceptor_cannot_silently_convert_Failure_to_Success() {
        // Capture what the inner chain returned so the assertion below
        // can verify that the runtime did detect (and reject) the silent
        // upgrade, rather than accidentally letting the Failure escape.
        AtomicReference<DispatchResult<?>> seen = new AtomicReference<>();

        DispatchInterceptor evil =
                new DispatchInterceptor() {
                    @Override
                    public <R> DispatchResult<R> intercept(InvocationContext ctx, DispatchChain<R> chain) {
                        DispatchResult<R> inner = chain.proceed();
                        seen.set(inner);
                        // Lie to the caller — pretend everything was fine.
                        return DispatchResult.success(null);
                    }
                };

        IllegalStateException  boom = new IllegalStateException("inner-failed");
        DispatchResult<String> r    =
                SyncDispatcher.dispatchThrough(
                                               rootCtx(), List.of(evil), () -> DispatchResult.failure(boom));

        // The inner chain genuinely failed; the evil interceptor saw it.
        assertInstanceOf(
                         DispatchResult.Failure.class,
                         seen.get(),
                         "precondition: the inner chain must have returned a Failure");

        // The outer dispatch must NOT surface Success — the runtime
        // detected the silent upgrade and converted it to a Technical.
        if (!(r instanceof DispatchResult.Failure<String> f)) {
            fail("evil interceptor's silent Failure→Success upgrade must be rejected; got " + r);
            return;
        }
        FlowError.Technical tech =
                assertInstanceOf(
                                 FlowError.Technical.class,
                                 f.cause(),
                                 "silent Failure→Success upgrade must surface as Technical");
        assertInstanceOf(
                         IllegalStateException.class,
                         tech.getCause(),
                         "Technical cause must explain the invariant violation");
        assertTrue(
                   tech.getCause().getMessage().contains("silently converted"),
                   "Technical message must call out the silent upgrade");
    }
}
