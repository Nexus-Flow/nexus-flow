package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the Phase 6 — and project-wide — invariant that {@link FlowScope#CURRENT_CONTEXT} (a {@link
 * ScopedValue}) is automatically inherited by virtual threads forked inside a {@link
 * StructuredTaskScope}.
 *
 * <p>This is the property that lets the dispatcher fan out work to multiple subtasks (event
 * fan-out, parallel handlers, durable outbox dispatch) without re-binding the context on each fork.
 * If a future refactor accidentally moves {@code CURRENT_CONTEXT} back to a {@code ThreadLocal}, or
 * someone forks via {@code Thread.startVirtualThread} outside the scope, this test will break
 * loudly.
 */
class FlowScopePropagatesAcrossStructuredTaskScopeTest {

    @Test
    @DisplayName("CURRENT_CONTEXT is inherited by every StructuredTaskScope.fork() subtask")
    void contextPropagatesAcrossForks() {
        ExecutionContext                       outer       = ExecutionContext.root();
        final int                              forks       = 64;
        AtomicReferenceArray<ExecutionContext> seen        = new AtomicReferenceArray<>(forks);
        AtomicInteger                          sameAsOuter = new AtomicInteger();

        FlowScope.runWithContext(
                                 outer,
                                 () -> {
                                     try (var scope = StructuredTaskScope.open(Joiner.awaitAllSuccessfulOrThrow())) {
                                         for (int i = 0; i < forks; i++) {
                                             final int idx = i;
                                             scope.fork(
                                                        () -> {
                                                            ExecutionContext captured = FlowScope.requireCurrent();
                                                            seen.set(idx, captured);
                                                            if (captured == outer) {
                                                                sameAsOuter.incrementAndGet();
                                                            }
                                                            return null;
                                                        });
                                         }
                                         scope.join();
                                     } catch (InterruptedException ie) {
                                         Thread.currentThread().interrupt();
                                         throw new AssertionError("interrupted", ie);
                                     }
                                 });

        assertEquals(forks, sameAsOuter.get(), "every fork must inherit the exact outer binding");
        for (int i = 0; i < forks; i++) {
            assertSame(outer, seen.get(i), "fork " + i + " must inherit the exact ScopedValue binding");
        }
    }

    @Test
    @DisplayName("Nested StructuredTaskScope inherits the inner-most CURRENT_CONTEXT")
    void nestedScopesSeeInnermostBinding() {
        ExecutionContext                  outer   = ExecutionContext.root();
        ExecutionContext                  inner   = outer.withAttribute("layer", "inner");
        AtomicReference<ExecutionContext> deepest = new AtomicReference<>();

        FlowScope.runWithContext(
                                 outer,
                                 () -> FlowScope.runWithContext(
                                                                inner,
                                                                () -> {
                                                                    try (var scope = StructuredTaskScope.open(Joiner
                                                                            .awaitAllSuccessfulOrThrow())) {
                                                                        scope.fork(
                                                                                   () -> {
                                                                                       deepest.set(FlowScope.requireCurrent());
                                                                                       return null;
                                                                                   });
                                                                        scope.join();
                                                                    } catch (InterruptedException ie) {
                                                                        Thread.currentThread().interrupt();
                                                                        throw new AssertionError("interrupted", ie);
                                                                    }
                                                                }));

        assertSame(inner, deepest.get(), "nested binding must shadow the outer one across forks");
    }
}
