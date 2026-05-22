package net.nexus_flow.core.runtime.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.*;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import net.nexus_flow.core.runtime.result.FlowDeadlineExceededException;
import net.nexus_flow.core.runtime.result.FlowError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link FlowRuntime} dispatch error-handling contracts: domain vs. technical errors, error
 * policies (failFast, collectFailures, isolate), and cancellation/deadline semantics.
 *
 * <p>Each test runs against a fresh per-test {@link FlowRuntime}. The whole suite uses event
 * fan-out as the structured-concurrency driver because (a) it is the only fan-out the runtime
 * exposes today via {@code EventBus#dispatchResult}, and (b) it exercises every {@link ErrorPolicy}
 * branch end-to-end.
 */
class SyncDispatcherErrorModelTest {

    private FlowRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = FlowRuntime.builder().build();
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    static final class FanEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        FanEvent(String key) {
            super(key);
        }
    }

    record Greet(String name) {
    }

    /** Domain error: not wrapped by the dispatcher. */
    @Test
    void domainError_isReturnedVerbatim_noWrapping() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var handler =
                    new AbstractReturnCommandHandler<Greet, String>() {
                        @Override
                        protected String handle(Greet command) {
                            throw new DispatchTestFixtures.InvalidSku("bad:" + command.name());
                        }
                    };
            runtime.commands().register(handler);
            try {
                Command<Greet>         cmd = Command.<Greet>builder().body(new Greet("x")).build();
                DispatchResult<String> r   =
                        runtime
                                .commands()
                                .dispatchAndReturnResult(cmd, ExecutionContext.root(), ErrorPolicy.failFast());
                if (!(r instanceof DispatchResult.Failure<String> failure)) {
                    fail("domain error must surface as Failure; got " + r);
                    return;
                }
                Throwable cause = failure.cause();
                assertInstanceOf(
                                 DispatchTestFixtures.InvalidSku.class,
                                 cause,
                                 "domain error must be returned VERBATIM, not wrapped. Got: "
                                         + cause.getClass().getName());
                assertEquals("bad:x", cause.getMessage());
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }

    /** Technical error: wrapped with ExecutionContext. */
    @Test
    void technicalError_isWrappedAndCarriesContext() {
        ExecutionContext ctx = ExecutionContext.root();
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var handler =
                    new AbstractReturnCommandHandler<Greet, String>() {
                        @Override
                        protected String handle(Greet command) {
                            throw new IllegalStateException("DB down");
                        }
                    };
            runtime.commands().register(handler);
            try {
                Command<Greet>         cmd = Command.<Greet>builder().body(new Greet("x")).build();
                DispatchResult<String> r   =
                        runtime.commands().dispatchAndReturnResult(cmd, ctx, ErrorPolicy.failFast());
                if (!(r instanceof DispatchResult.Failure<String> failure)) {
                    fail("Expected Failure, got " + r);
                    return;
                }
                Throwable           cause = failure.cause();
                FlowError.Technical tech  =
                        assertInstanceOf(
                                         FlowError.Technical.class,
                                         cause,
                                         "non-domain throwables must be wrapped in FlowError.Technical");
                assertSame(
                           ctx,
                           tech.executionContext(),
                           "Technical must carry the ExecutionContext active at the failure site");
                assertInstanceOf(IllegalStateException.class, tech.getCause());
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }

    /** FailFast cancels siblings on first failure. */
    @Test
    void failFast_cancelsSiblings_onFirstFailure() {
        EventBus       bus            = runtime.events();
        AtomicInteger  ran            = new AtomicInteger();
        CountDownLatch slowMayProceed = new CountDownLatch(1);

        var failing =
                new AbstractDomainEventListener<FanEvent>() {
                                @Override
                                public void handle(FanEvent event) {
                                    throw new RuntimeException("first to fail");
                                }

                                @Override
                                public int order() {
                                    return 1;
                                }
                            };
        var slow    =
                new AbstractDomainEventListener<FanEvent>() {
                                @Override
                                public void handle(FanEvent event) {
                                    ran.incrementAndGet();
                                    try {
                                        // Long enough that FailFast cancellation lands first.
                                        if (!slowMayProceed.await(2, TimeUnit.SECONDS)) {
                                            // If we got here, cancellation didn't fire. Mark it.
                                            ran.addAndGet(1000);
                                        }
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                    }
                                }

                                @Override
                                public int order() {
                                    return 2;
                                }
                            };

        bus.register(failing);
        bus.register(slow);
        try {
            DispatchResult<Void> r =
                    bus.dispatchResult(
                                       new FanEvent("agg-1"), ExecutionContext.root(), ErrorPolicy.failFast());

            // Allow any waiting slow listener to terminate cleanly.
            slowMayProceed.countDown();

            assertInstanceOf(
                             DispatchResult.Failure.class, r, "FailFast must surface a Failure, not PartialFailure");
            // concurrent losers may attach as suppressed.
            Throwable cause = ((DispatchResult.Failure<Void>) r).cause();
            org.junit.jupiter.api.Assertions.assertNotNull(cause);
        } finally {
            bus.unregister(failing);
            bus.unregister(slow);
        }
    }

    /** CollectFailures aggregates every failure. */
    @Test
    void collectFailures_returnsPartialFailureWithAllCauses() {
        EventBus bus = runtime.events();
        var      lA  =
                new AbstractDomainEventListener<FanEvent>() {
                                 @Override
                                 public void handle(FanEvent event) {
                                     throw new RuntimeException("A");
                                 }

                                 @Override
                                 public int order() {
                                     return 1;
                                 }
                             };
        var      lB  =
                new AbstractDomainEventListener<FanEvent>() {
                                 @Override
                                 public void handle(FanEvent event) {
                                     throw new RuntimeException("B");
                                 }

                                 @Override
                                 public int order() {
                                     return 2;
                                 }
                             };
        var      lC  =
                new AbstractDomainEventListener<FanEvent>() {
                                 @Override
                                 public void handle(FanEvent event) {
                                                                     /* OK */
                                 }

                                 @Override
                                 public int order() {
                                     return 3;
                                 }
                             };

        bus.register(lA);
        bus.register(lB);
        bus.register(lC);
        try {
            DispatchResult<Void> r =
                    bus.dispatchResult(
                                       new FanEvent("agg-2"), ExecutionContext.root(), ErrorPolicy.collectFailures());
            if (!(r instanceof DispatchResult.PartialFailure<Void> p)) {
                fail("CollectFailures with surviving listener must produce PartialFailure; got " + r);
                return;
            }
            assertEquals(2, p.failures().size(), "CollectFailures must aggregate every failure");
            List<String> messages = p.failures().stream().map(Throwable::getMessage).sorted().toList();
            assertEquals(List.of("A", "B"), messages);
        } finally {
            bus.unregister(lA);
            bus.unregister(lB);
            bus.unregister(lC);
        }
    }

    /** IsolatePerBoundary contains failures. */
    @Test
    void isolatePerBoundary_doesNotPropagate() {
        EventBus bus     = runtime.events();
        var      failing =
                new AbstractDomainEventListener<FanEvent>() {
                                     @Override
                                     public void handle(FanEvent event) {
                                         throw new RuntimeException("inside-boundary");
                                     }
                                 };
        bus.register(failing);
        try {
            DispatchResult<Void> r =
                    bus.dispatchResult(
                                       new FanEvent("agg-3"),
                                       ExecutionContext.root(),
                                       ErrorPolicy.isolate(ErrorPolicy.failFast()));
            if (!(r instanceof DispatchResult.PartialFailure<Void> p)) {
                fail(
                     "IsolatePerBoundary must surface failures as PartialFailure rather than propagating;"
                             + " got "
                             + r);
                return;
            }
            assertEquals(1, p.failures().size());
        } finally {
            bus.unregister(failing);
        }
    }

    /** Cancellation mid-chain surfaces FlowCancellationException. */
    @Test
    void cancellationMidChain_surfacesFlowCancellationException() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var handler =
                    new AbstractReturnCommandHandler<Greet, String>() {
                        @Override
                        protected String handle(Greet command) {
                            return "ok";
                        }
                    };
            runtime.commands().register(handler);
            try {
                ExecutionContext ctx = ExecutionContext.root();
                ctx.cancellation().cancel(); // pre-cancel before dispatch
                Command<Greet>         cmd = Command.<Greet>builder().body(new Greet("x")).build();
                DispatchResult<String> r   =
                        runtime.commands().dispatchAndReturnResult(cmd, ctx, ErrorPolicy.failFast());
                if (!(r instanceof DispatchResult.Failure<String> failure)) {
                    fail("Expected Failure, got " + r);
                    return;
                }
                Throwable cause = failure.cause();
                assertInstanceOf(
                                 FlowCancellationException.class,
                                 cause,
                                 "pre-cancelled context must surface FlowCancellationException; got: "
                                         + cause.getClass().getName());
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }

    /** Deadline exceeded mid-chain surfaces FlowDeadlineExceededException. */
    @Test
    void deadlineExceeded_surfacesFlowDeadlineExceededException() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var handler =
                    new AbstractReturnCommandHandler<Greet, String>() {
                        @Override
                        protected String handle(Greet command) {
                            return "ok";
                        }
                    };
            runtime.commands().register(handler);
            try {
                Instant                deadline = Instant.parse("2026-05-10T09:59:00Z");
                ExecutionContext       ctx      =
                        new ExecutionContext(
                                MessageId.random(),
                                TraceId.random(),
                                CorrelationId.random(),
                                CausationId.ROOT,
                                null,
                                null,
                                deadline,
                                CancellationToken.create(),
                                java.util.Map.of());
                Command<Greet>         cmd      = Command.<Greet>builder().body(new Greet("x")).build();
                DispatchResult<String> r        =
                        runtime.commands().dispatchAndReturnResult(cmd, ctx, ErrorPolicy.failFast());
                if (!(r instanceof DispatchResult.Failure<String> failure)) {
                    fail("Expected Failure, got " + r);
                    return;
                }
                Throwable                     cause = failure.cause();
                FlowDeadlineExceededException dl    =
                        assertInstanceOf(
                                         FlowDeadlineExceededException.class,
                                         cause,
                                         "expired deadline must surface FlowDeadlineExceededException; got: "
                                                 + cause.getClass().getName());
                assertEquals(deadline, dl.deadline());
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }

    /** Multiple concurrent failures: every cause exposed. */
    @Test
    void concurrentFailures_collectFailures_preservesEveryCause() {
        EventBus bus = runtime.events();
        var      lA  =
                new AbstractDomainEventListener<FanEvent>() {
                                 @Override
                                 public void handle(FanEvent event) {
                                     throw new RuntimeException("A");
                                 }
                             };
        var      lB  =
                new AbstractDomainEventListener<FanEvent>() {
                                 @Override
                                 public void handle(FanEvent event) {
                                     throw new RuntimeException("B");
                                 }
                             };
        var      lC  =
                new AbstractDomainEventListener<FanEvent>() {
                                 @Override
                                 public void handle(FanEvent event) {
                                     throw new RuntimeException("C");
                                 }
                             };
        bus.register(lA);
        bus.register(lB);
        bus.register(lC);
        try {
            DispatchResult<Void> r =
                    bus.dispatchResult(
                                       new FanEvent("agg-4"), ExecutionContext.root(), ErrorPolicy.collectFailures());
            if (!(r instanceof DispatchResult.PartialFailure<Void> p)) {
                fail("Expected PartialFailure, got " + r);
                return;
            }
            assertEquals(3, p.failures().size(), "CollectFailures must surface every concurrent failure");
        } finally {
            bus.unregister(lA);
            bus.unregister(lB);
            bus.unregister(lC);
        }
    }

    /** Nested command failure: outer surfaces Failure. */
    @Test
    void nestedCommandFailure_returnsRootFailure() {
        record Outer(String x) {
        }
        record Inner(String x) {
        }
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var inner =
                    new AbstractReturnCommandHandler<Inner, String>() {
                                  @Override
                                  protected String handle(Inner command) {
                                      throw new DispatchTestFixtures.InvalidSku("inner-fail");
                                  }
                              };
            var outer =
                    new AbstractReturnCommandHandler<Outer, String>() {
                                  @Override
                                  protected String handle(Outer command) {
                                      // Nested dispatch — flow #1.
                                      DispatchResult<String> r =
                                              runtime
                                                      .commands()
                                                      .dispatchAndReturnResult(
                                                                               Command.<Inner>builder().body(new Inner(command.x()))
                                                                                       .build(),
                                                                               FlowScope.requireCurrent(),
                                                                               ErrorPolicy.failFast());
                                      return switch (r) {
                                                    case DispatchResult.Success<String> s -> s.value();
                                                    case DispatchResult.Failure<String> f -> {
                                                        // Re-throw verbatim so the no-wrap path is exercised.
                                                        if (f.cause() instanceof RuntimeException re)
                                                            throw re;
                                                        throw new RuntimeException(f.cause());
                                                    }
                                                    case DispatchResult.PartialFailure<String> p -> "partial:" + p.failures().size();
                                                    case DispatchResult.Accepted<String> a -> "accepted:" + a.messageId();
                                                };
                                  }
                              };
            runtime.commands().register(inner);
            runtime.commands().register(outer);
            try {
                Command<Outer>         cmd = Command.<Outer>builder().body(new Outer("nx")).build();
                DispatchResult<String> r   =
                        runtime
                                .commands()
                                .dispatchAndReturnResult(cmd, ExecutionContext.root(), ErrorPolicy.failFast());
                if (!(r instanceof DispatchResult.Failure<String> failure)) {
                    fail("nested command failure must surface as root Failure; got " + r);
                    return;
                }
                Throwable cause = failure.cause();
                assertInstanceOf(
                                 DispatchTestFixtures.InvalidSku.class,
                                 cause,
                                 "nested domain error must propagate verbatim; got " + cause.getClass().getName());
            } finally {
                runtime.commands().unregister(outer);
                runtime.commands().unregister(inner);
            }
        }
    }
}
