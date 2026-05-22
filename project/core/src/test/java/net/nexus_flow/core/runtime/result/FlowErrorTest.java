package net.nexus_flow.core.runtime.result;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.List;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * {@link FlowError} contract: domain vs. technical error wrapping, technical context attachment.
 *
 * <p>Three load-bearing requirements:
 *
 * <ul>
 * <li>{@link FlowError.Domain} is a marker interface; user exceptions opt in by implementing it.
 * The runtime must propagate them <strong>verbatim</strong> — no wrapping, no
 * execution-context attachment.
 * <li>{@link FlowError.Technical} is a {@code RuntimeException} that carries both the original
 * cause and the {@link ExecutionContext} active at the failure site.
 * <li>{@link FlowError.Aggregated} collapses N failures into a single throwable, attaching every
 * loser as a suppressed exception. It is consumed by {@link ErrorPolicy.CollectFailures}.
 * </ul>
 */
class FlowErrorTest {

    /** A user-defined domain exception opting into the no-wrap path. */
    static final class InvalidSku extends RuntimeException implements FlowError.Domain {
        @Serial
        private static final long serialVersionUID = 1L;

        InvalidSku(String message) {
            super(message);
        }
    }

    @Test
    void domain_isAMarkerInterface_userExceptionsOptIn() {
        InvalidSku original = new InvalidSku("invalid SKU");

        // The runtime check is plain instanceof — that is the whole point
        // of Domain being a marker interface.
        assertInstanceOf(FlowError.class, original);
        assertInstanceOf(FlowError.Domain.class, original);

        // No wrapping anywhere: a Domain error IS the throwable.
        assertSame(original, original.asThrowable());
    }

    @Test
    void technical_isARuntimeException_carryingCauseAndExecutionContext() {
        ExecutionContext      ctx  = ExecutionContext.root();
        IllegalStateException root = new IllegalStateException("DB down");

        FlowError.Technical tech = new FlowError.Technical(root, ctx);

        // Technical IS a Throwable — fits DispatchResult.Failure(Throwable)
        // and any tooling that walks getCause()/getSuppressed().
        assertInstanceOf(RuntimeException.class, tech);
        assertInstanceOf(FlowError.class, tech);
        assertSame(
                   root,
                   tech.getCause(),
                   "Technical must preserve the original cause via Throwable.getCause()");
        assertSame(
                   ctx,
                   tech.executionContext(),
                   "Technical must always carry the ExecutionContext active at the failure site");
        assertEquals("DB down", tech.getMessage());
    }

    @Test
    void technical_acceptsExplicitMessage() {
        ExecutionContext ctx  = ExecutionContext.root();
        Throwable        root = new RuntimeException("boom");

        FlowError.Technical tech = new FlowError.Technical("dispatch failed", root, ctx);
        assertEquals("dispatch failed", tech.getMessage());
        assertSame(root, tech.getCause());
    }

    @Test
    void technical_rejectsNullArguments() {
        ExecutionContext ctx = ExecutionContext.root();
        assertThrows(NullPointerException.class, () -> new FlowError.Technical(null, ctx));
        assertThrows(
                     NullPointerException.class, () -> new FlowError.Technical(new RuntimeException(), null));
        assertThrows(NullPointerException.class, () -> new FlowError.Technical("msg", null, ctx));
    }

    @Test
    void switchExpression_overFlowError_isExhaustive() {
        FlowError sample = new FlowError.Technical(new RuntimeException("x"), ExecutionContext.root());
        String    tag    =
                switch (sample) {
                                     case FlowError.Domain d -> "domain:" + d.asThrowable().getMessage();
                                     case FlowError.Technical t -> "technical:" + t.getMessage();
                                     case FlowError.Aggregated a -> "aggregated:" + a.failures().size();
                                 };
        assertEquals("technical:x", tag);
    }

    @Test
    void technical_fitsInsideDispatchResultFailure() {
        // DispatchResult.Failure(FlowError.Technical(cause, ctx))
        // must compile cleanly because Technical is a Throwable.
        FlowError.Technical  tech   =
                new FlowError.Technical(new RuntimeException("x"), ExecutionContext.root());
        DispatchResult<Void> result = DispatchResult.failure(tech);
        assertInstanceOf(DispatchResult.Failure.class, result);
        assertSame(tech, ((DispatchResult.Failure<Void>) result).cause());
    }

    @Test
    void aggregated_attachesEveryFailureAsSuppressed_andExposesTheList() {
        Throwable a = new RuntimeException("a");
        Throwable b = new RuntimeException("b");
        Throwable c = new RuntimeException("c");

        FlowError.Aggregated agg = new FlowError.Aggregated(List.of(a, b, c));

        assertInstanceOf(FlowError.class, agg);
        assertEquals(List.of(a, b, c), agg.failures());

        // First element is the cause to keep stack-trace printing readable.
        assertSame(a, agg.getCause());

        // The remaining failures are attached as suppressed.
        Throwable[] suppressed = agg.getSuppressed();
        assertEquals(2, suppressed.length);
        assertSame(b, suppressed[0]);
        assertSame(c, suppressed[1]);
    }

    @Test
    void aggregated_singleFailure_keepsCause_andHasNoSuppressed() {
        Throwable a = new RuntimeException("solo");

        FlowError.Aggregated agg = new FlowError.Aggregated(List.of(a));
        assertSame(a, agg.getCause());
        assertEquals(0, agg.getSuppressed().length, "first failure is the cause; no self-suppression");
    }

    @Test
    void aggregated_rejectsEmptyOrNull() {
        assertThrows(IllegalArgumentException.class, () -> new FlowError.Aggregated(List.of()));
        assertThrows(NullPointerException.class, () -> new FlowError.Aggregated(null));
    }
}
