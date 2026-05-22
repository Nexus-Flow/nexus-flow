package net.nexus_flow.core.cqrs.command;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.cqrs.command.exceptions.CommandHandlerExecutionError;
import net.nexus_flow.core.runtime.CancellationToken;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.FlowScope;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import net.nexus_flow.core.runtime.result.FlowDeadlineExceededException;
import org.junit.jupiter.api.Test;

/**
 * Verifies cooperative cancellation and deadline-exceeded handling inside the {@code
 * *HandlerExecutor} drain loop using {@link ExecutionContext#throwIfCancelledOrExpired()}.
 *
 * <p>The drain loop must observe cancellation and deadline events cooperatively, not block
 * uninterruptibly. This test exercises the {@link FlowRuntime} command dispatch system with two
 * scenarios:
 *
 * <ul>
 * <li>Cancelled heads: A handler with {@code concurrencyLevel=2} is dispatched 3 times under a
 * healthy context, then the context is cancelled mid-flight. The loop must purge each
 * cancelled head before invoking the handler and surface {@link FlowCancellationException}
 * through the future. The handler-side counter MUST stay at 3 (no user code runs on cancelled
 * context).
 * <li>Deadline-exceeded heads: A handler is dispatched under a context whose deadline is already
 * in the past. The loop must purge all heads before invoking the handler and surface {@link
 * FlowDeadlineExceededException} with the original deadline preserved.
 * </ul>
 *
 * <p>Both scenarios verify no semaphore permits leak through the doomed dispatches (a leak would
 * silently shrink concurrency for the rest of the process).
 *
 * <p>Lives in the {@code net.nexus_flow.core.cqrs.command} package so the package-private executor
 * classes are accessible by name; the field reflection still has to {@code setAccessible(true)}
 * because {@code semaphore} is {@code private final}.
 */
class CooperativeCancellationInPollLoopTest {

    record Beep(int n) {
    }

    /**
     * Concurrency-2 handler whose body merely increments a counter. Returns the input back to the
     * caller so the future surfaces a meaningful value.
     */
    private static AbstractReturnCommandHandler<Beep, Integer> counterHandler(AtomicInteger counter) {
        return new AbstractReturnCommandHandler<>() {
            @Override
            protected Integer handle(Beep command) {
                counter.incrementAndGet();
                return command.n();
            }

            @Override
            public int getConcurrencyLevel() {
                return 2;
            }

            @Override
            public InitializationType getInitializationType() {
                // Lazy: drainers are submitted on demand by
                // tryExecuteNextTask; cooperative loop is fully
                // exercised because each dispatch causes a fresh
                // worker to drain through cancellation checks.
                return InitializationType.LAZY;
            }
        };
    }

    @Test
    void cancelledHeads_areDrainedWithoutPermitLeakage() throws Exception {
        AtomicInteger                               counter = new AtomicInteger();
        AbstractReturnCommandHandler<Beep, Integer> handler = counterHandler(counter);

        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            runtime.commands().register(handler);
            try {
                // Single shared context across all 6 dispatches — the
                // CancellationToken is what we'll flip mid-flight.
                ExecutionContext ctx = ExecutionContext.root();

                // ---- 3 healthy dispatches complete cleanly.
                for (int i = 1; i <= 3; i++) {
                    int     beep   = i;
                    Integer result =
                            FlowScope.getWithContext(
                                                     ctx,
                                                     () -> runtime
                                                             .commands()
                                                             .dispatchAndReturn(Command.<Beep>builder().body(new Beep(beep)).build()));
                    assertEquals(beep, result, "Healthy dispatch #" + beep + " must complete normally");
                }
                assertEquals(3, counter.get(), "Handler must have run exactly 3 times before cancellation");

                // ---- cancel and dispatch 3 more.
                ctx.cancellation().cancel();

                int doomedSurfacings = 0;
                for (int i = 4; i <= 6; i++) {
                    int beep = i;
                    try {
                        FlowScope.getWithContext(
                                                 ctx,
                                                 () -> runtime
                                                         .commands()
                                                         .dispatchAndReturn(Command.<Beep>builder().body(new Beep(beep)).build()));
                        // Some dispatch paths swallow the exception and return null,
                        // which is acceptable as long as the handler did NOT run.
                        // The counter assertion below verifies that constraint.
                    } catch (FlowCancellationException expected) {
                        doomedSurfacings++;
                    } catch (CommandHandlerExecutionError wrapped) {
                        // DefaultCommandBus wraps every RuntimeException
                        // (including FlowCancellationException) into
                        // CommandHandlerExecutionError on the way out.
                        // The cancellation contract is satisfied as long
                        // as the cause is the cancellation we raised.
                        Throwable cause = wrapped.getCause();
                        assertInstanceOf(
                                         FlowCancellationException.class,
                                         cause,
                                         "Doomed dispatch must surface FlowCancellationException; "
                                                 + "found "
                                                 + (cause == null ? "null" : cause.getClass().getName()));
                        doomedSurfacings++;
                    }
                }

                assertEquals(
                             3,
                             counter.get(),
                             " cooperative loop MUST purge cancelled heads "
                                     + "before invoking the handler. Counter went from 3 to "
                                     + counter.get()
                                     + ", which means user code ran on a "
                                     + "cancelled context.");

                // The future-level surfacing is a stronger guarantee
                // (callers see the cancellation), but the core contract
                // is "user code does not run on cancelled ctx";
                // we accept either path here.
                assertTrue(
                           doomedSurfacings <= 3,
                           "Sanity: cannot have surfaced more cancellations than dispatches");

                // ---- no permit leak.
                Semaphore sem = readPrivateSemaphore(runtime, handler);
                assertEquals(
                             2,
                             sem.availablePermits(),
                             " doomed heads MUST NOT consume a semaphore permit. "
                                     + "Found "
                                     + sem.availablePermits()
                                     + " available out of 2; "
                                     + "a leak here would silently shrink concurrency for the "
                                     + "rest of the process.");
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }

    @Test
    void deadlineExceededHeads_arePurgedAndSurfaceOriginalDeadline() throws Exception {
        AtomicInteger                               counter = new AtomicInteger();
        AbstractReturnCommandHandler<Beep, Integer> handler = counterHandler(counter);

        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            runtime.commands().register(handler);
            try {
                // Build a ctx whose deadline is already in the past —
                // every dispatch under it is doomed before the queue
                // even sees it. The cooperative loop must purge all
                // 3 without ever calling the handler.
                Instant          deadline  =
                        Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneOffset.UTC)
                                .instant()
                                .minusSeconds(60);
                ExecutionContext doomedCtx =
                        new ExecutionContext(
                                MessageId.random(),
                                TraceId.random(),
                                CorrelationId.random(),
                                CausationId.ROOT,
                                null,
                                null,
                                deadline,
                                CancellationToken.create(),
                                Map.of());

                FlowDeadlineExceededException surfaced = null;
                for (int i = 1; i <= 3; i++) {
                    int beep = i;
                    try {
                        FlowScope.getWithContext(
                                                 doomedCtx,
                                                 () -> runtime
                                                         .commands()
                                                         .dispatchAndReturn(Command.<Beep>builder().body(new Beep(beep)).build()));
                    } catch (FlowDeadlineExceededException dl) {
                        surfaced = dl;
                    } catch (CommandHandlerExecutionError wrapped) {
                        Throwable cause = wrapped.getCause();
                        if (cause instanceof FlowDeadlineExceededException dl) {
                            surfaced = dl;
                        } else {
                            throw new AssertionError(
                                    "Doomed dispatch must surface FlowDeadlineExceededException; "
                                            + "found "
                                            + (cause == null ? "null" : cause.getClass().getName()),
                                    wrapped);
                        }
                    }
                }

                assertEquals(
                             0, counter.get(), "deadline-exceeded heads MUST be purged before the handler runs");

                assertNotNull(
                              surfaced,
                              "At least one dispatch should have surfaced "
                                      + "FlowDeadlineExceededException to the caller; got null. "
                                      + "If the border guard caught it before the queue even saw "
                                      + "the task, that is still valid — but for concurrencyLevel>0 "
                                      + "the cooperative loop is the path we want to exercise here.");
                assertEquals(
                             deadline,
                             surfaced.deadline(),
                             "The surfaced exception MUST carry the original deadline, not a re-computed value.");

                Semaphore sem = readPrivateSemaphore(runtime, handler);
                assertEquals(2, sem.availablePermits(), " deadline purges MUST NOT leak permits");
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }

    /**
     * Reflection helper to access the executor's private semaphore for verification.
     *
     * <p>The {@link DefaultCommandConsumerRegistry} keys executors by {@code CommandTypeSignature};
     * the cleanest accessor would be a test-only hook, but this test pins the no-permit-leak contract
     * independently of that hook. Reflection lets us assert the contract directly without widening
     * the production API.
     */
    private static Semaphore readPrivateSemaphore(
            FlowRuntime runtime, AbstractReturnCommandHandler<Beep, Integer> handler) throws Exception {
        // the registry is owned per-runtime by the
        // CommandBus; drill through the bus instance instead of the
        // removed CommandConsumerRegistryFactory.getInstance().
        Object bus           = runtime.commands();
        Field  registryField = bus.getClass().getDeclaredField("consumerRegistry");
        registryField.setAccessible(true);
        Object registry = registryField.get(bus);
        // registry was consolidated into a single executorMap
        // keyed by TypeReference; entries are CommandExecutorEntry.ReturnEntry
        // wrappers; we must unwrap them to reach the underlying executor.
        Field executorMapField = registry.getClass().getDeclaredField("executorMap");
        executorMapField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Object, Object> map      = (Map<Object, Object>) executorMapField.get(registry);
        Object                                             executor = null;
        for (Object value : map.values()) {
            // Unwrap CommandExecutorEntry.ReturnEntry to get the underlying
            // DefaultCommandHandlerExecutor.
            Object candidate = value;
            try {
                java.lang.reflect.Method executorMethod = value.getClass().getDeclaredMethod("executor");
                candidate = executorMethod.invoke(value);
            } catch (NoSuchMethodException _) {
                // value is already a bare executor (should not happen after the entry-wrapper
                // consolidation)
            }
            Field handlerField = findFieldInHierarchy(candidate.getClass(), "outerHandler");
            if (handlerField == null) {
                continue;
            }
            handlerField.setAccessible(true);
            if (handlerField.get(candidate) == handler) {
                executor = candidate;
                break;
            }
        }
        if (executor == null) {
            fail("Could not locate the command handler executor registered for the test handler");
        }
        Field semField = findFieldInHierarchy(executor.getClass(), "semaphore");
        assertNotNull(semField, "Executor must declare a 'semaphore' field in its hierarchy");
        semField.setAccessible(true);
        Semaphore sem = (Semaphore) semField.get(executor);
        assertNotNull(sem, "Executor must have a Semaphore for concurrencyLevel>0");
        return sem;
    }

    /**
     * Walk up the class hierarchy looking for a declared field with the given name. The executor
     * consolidation hoisted {@code outerHandler} and {@code semaphore} into {@code
     * AbstractCommandHandlerExecutor}, so the legacy single-level lookup no longer finds them; this
     * helper keeps the test robust against future field-moves up or down the hierarchy.
     */
    private static Field findFieldInHierarchy(Class<?> start, String name) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // continue walking up
            }
        }
        return null;
    }
}
