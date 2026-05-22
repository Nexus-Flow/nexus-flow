package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import net.nexus_flow.core.runtime.result.FlowDeadlineExceededException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Inheritance contract of {@link ExecutionContext#childContextFor(MessageId)}.
 *
 * <p>{@code traceId}, {@code correlationId}, {@code deadline}, {@code cancellation} and {@code
 * attributes} must be preserved across nested dispatch; {@code messageId} must be replaced; {@code
 * causationId} must be chained to the parent's {@code messageId}.
 */
class ExecutionContextChildContextTest {

    @Test
    void child_inheritsTraceCorrelationAndCancellationFromParent() {
        ExecutionContext parent  = ExecutionContext.root();
        MessageId        childId = MessageId.random();

        ExecutionContext child = parent.childContextFor(childId);

        assertEquals(
                     parent.traceId(), child.traceId(), "traceId must be constant across the whole flow");
        assertEquals(
                     parent.correlationId(),
                     child.correlationId(),
                     "correlationId must be constant across the whole conceptual operation");
        assertSame(
                   parent.cancellation(),
                   child.cancellation(),
                   "cancellation must be the same instance — cancelling the parent "
                           + "must be observable in the child");
    }

    @Test
    void child_adoptsNewMessageId_andChainesCausationToParent() {
        ExecutionContext parent  = ExecutionContext.root();
        MessageId        childId = MessageId.random();

        ExecutionContext child = parent.childContextFor(childId);

        assertEquals(childId, child.messageId());
        assertNotEquals(parent.messageId(), child.messageId());
        assertEquals(
                     new CausationId(parent.messageId().value()),
                     child.causationId(),
                     "child causation must equal the parent's messageId");
    }

    @Test
    void rootContext_hasRootCausation() {
        ExecutionContext root = ExecutionContext.root();
        assertEquals(CausationId.ROOT, root.causationId());
    }

    @Test
    void child_inheritsDeadline() {
        Clock            fixed  = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneOffset.UTC);
        ExecutionContext parent = ExecutionContext.rootWithTimeout(Duration.ofSeconds(5), fixed);
        ExecutionContext child  = parent.childContextFor(MessageId.random());

        assertEquals(
                     parent.deadline(), child.deadline(), "deadline is shared between parent and children");
    }

    @Test
    void child_inheritsAttributes_byValue() {
        ExecutionContext parent = ExecutionContext.root().withAttribute("tenant", "ACME");
        ExecutionContext child  = parent.childContextFor(MessageId.random());

        assertEquals("ACME", child.attributes().get("tenant"));
    }

    @Test
    void withAttribute_doesNotMutateParent() {
        // Attribute mutation is copy-on-write: deriving a new context with an
        // extra attribute must not bleed into the original instance.
        ExecutionContext parent  = ExecutionContext.root();
        ExecutionContext mutated = parent.withAttribute("tenant", "ACME");

        assertEquals(0, parent.attributes().size(), "parent attributes must remain untouched");
        assertEquals("ACME", mutated.attributes().get("tenant"));
        assertNotNull(mutated);
    }

    @Test
    void childContextFor_rejectsNullMessageId() {
        ExecutionContext parent = ExecutionContext.root();
        assertThrows(NullPointerException.class, () -> parent.childContextFor(null));
    }

    @Test
    void parentCancellation_isObservableByChild() {
        ExecutionContext parent = ExecutionContext.root();
        ExecutionContext child  = parent.childContextFor(MessageId.random());

        parent.cancellation().cancel();

        assertEquals(
                     true,
                     child.cancellation().isCancellationRequested(),
                     "child observes the parent's cancellation through the shared token");
        assertThrows(FlowCancellationException.class, child::throwIfCancelledOrExpired);
    }

    @Test
    void grandchildCausation_chainesThroughIntermediateContext() {
        ExecutionContext root  = ExecutionContext.root();
        MessageId        midId = MessageId.random();
        ExecutionContext mid   = root.childContextFor(midId);

        MessageId        leafId = MessageId.random();
        ExecutionContext leaf   = mid.childContextFor(leafId);

        assertEquals(
                     midId.value(),
                     leaf.causationId().value(),
                     "leaf causation must point at its direct parent (mid)");
        assertEquals(root.traceId(), leaf.traceId());
        assertEquals(root.correlationId(), leaf.correlationId());
    }

    @Test
    void deadlineExpiry_isDetectedAcrossChildContexts() {
        Instant t0         = Instant.parse("2026-05-10T10:00:00Z");
        Clock   atDeadline = Clock.fixed(t0.plus(Duration.ofSeconds(10)), ZoneOffset.UTC);

        ExecutionContext parent =
                ExecutionContext.rootWithTimeout(Duration.ofSeconds(1), Clock.fixed(t0, ZoneOffset.UTC));
        ExecutionContext child  = parent.childContextFor(MessageId.random());

        assertThrows(
                     FlowDeadlineExceededException.class, () -> child.throwIfCancelledOrExpired(atDeadline));
    }

    @Test
    void attributes_areImmutable_onTheReturnedMap() {
        ExecutionContext    ctx   = ExecutionContext.root().withAttribute("k", "v");
        Map<String, Object> attrs = ctx.attributes();
        assertThrows(UnsupportedOperationException.class, () -> attrs.put("x", "y"));
    }

    /**
     * Deadline boundary testing: throwIfCancelledOrExpired() at the exact transition point.
     *
     * <p>Uses fixed Clock instances to test behavior when now == deadline, now + 1ns, and now - 1ns.
     */
    @Nested
    class DeadlineBoundary {

        @Test
        void justBeforeDeadline_doesNotThrow() {
            // deadline is 5 seconds from t0, but we check at t0 + 4.999999999 seconds
            Instant          t0             = Instant.parse("2026-05-10T10:00:00Z");
            Instant          deadline       = t0.plus(Duration.ofSeconds(5));
            Clock            beforeDeadline = Clock.fixed(deadline.minusNanos(1), ZoneOffset.UTC);
            ExecutionContext ctx            =
                    ExecutionContext.rootWithTimeout(Duration.ofSeconds(5), Clock.fixed(t0, ZoneOffset.UTC));
            // Must not throw when now < deadline
            assertDoesNotThrow(
                               () -> ctx.throwIfCancelledOrExpired(beforeDeadline),
                               "throwIfCancelledOrExpired must not throw when now < deadline");
        }

        @Test
        void atExactDeadline_throwsFlowDeadlineExceededException() {
            Instant          t0         = Instant.parse("2026-05-10T10:00:00Z");
            Instant          deadline   = t0.plus(Duration.ofSeconds(5));
            Clock            atDeadline = Clock.fixed(deadline, ZoneOffset.UTC);
            ExecutionContext ctx        =
                    ExecutionContext.rootWithTimeout(Duration.ofSeconds(5), Clock.fixed(t0, ZoneOffset.UTC));
            assertThrows(
                         FlowDeadlineExceededException.class,
                         () -> ctx.throwIfCancelledOrExpired(atDeadline),
                         "throwIfCancelledOrExpired must throw when now == deadline");
        }

        @Test
        void justAfterDeadline_throwsFlowDeadlineExceededException() {
            Instant          t0            = Instant.parse("2026-05-10T10:00:00Z");
            Instant          deadline      = t0.plus(Duration.ofSeconds(5));
            Clock            afterDeadline = Clock.fixed(deadline.plusNanos(1), ZoneOffset.UTC);
            ExecutionContext ctx           =
                    ExecutionContext.rootWithTimeout(Duration.ofSeconds(5), Clock.fixed(t0, ZoneOffset.UTC));
            assertThrows(
                         FlowDeadlineExceededException.class,
                         () -> ctx.throwIfCancelledOrExpired(afterDeadline),
                         "throwIfCancelledOrExpired must throw when now > deadline");
        }
    }
}
