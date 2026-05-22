package net.nexus_flow.core.runtime.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Instant;
import java.util.List;
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
import org.junit.jupiter.api.Test;

/**
 * {@link FlowRuntime} dispatch contracts with zero interceptors: byte-identical to pre-2.4 shape.
 *
 * <p>Tests that with no interceptors registered, every {@link DispatchResult} variant, cause class,
 * and key field remain identical to the legacy shape. This includes the fast path where {@code
 * dispatchThrough} bypasses the onion chain.
 */
class ZeroInterceptorRegressionTest {

    static final class FanEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        FanEvent(String key) {
            super(key);
        }
    }

    record Greet(String name) {
    }

    private FlowRuntime newRuntimeWithZeroInterceptors() {
        FlowRuntime runtime = FlowRuntime.builder().build();
        assertTrue(runtime.interceptors().isEmpty(), "precondition: zero interceptors registered");
        return runtime;
    }

    // Nested command failure: outer surfaces Failure.
    @Test
    void nestedCommandFailure_returnsRootFailure_zeroInterceptors() {
        record Outer(String x) {
        }
        record Inner(String x) {
        }
        try (FlowRuntime runtime = newRuntimeWithZeroInterceptors()) {
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
                DispatchResult<String> r =
                        runtime
                                .commands()
                                .dispatchAndReturnResult(
                                                         Command.<Outer>builder().body(new Outer("nx")).build(),
                                                         ExecutionContext.root(),
                                                         ErrorPolicy.failFast());
                var                    f = assertInstanceOf(DispatchResult.Failure.class, r);
                assertInstanceOf(
                                 DispatchTestFixtures.InvalidSku.class,
                                 f.cause(),
                                 "nested domain error must propagate verbatim with 0 interceptors");
            } finally {
                runtime.commands().unregister(outer);
                runtime.commands().unregister(inner);
            }
        }
    }

    // Domain error: returned verbatim with zero interceptors.
    @Test
    void domainError_isReturnedVerbatim_zeroInterceptors() {
        try (FlowRuntime runtime = newRuntimeWithZeroInterceptors()) {
            var handler =
                    new AbstractReturnCommandHandler<Greet, String>() {
                        @Override
                        protected String handle(Greet command) {
                            throw new DispatchTestFixtures.InvalidSku("bad:" + command.name());
                        }
                    };
            runtime.commands().register(handler);
            try {
                DispatchResult<String> r =
                        runtime
                                .commands()
                                .dispatchAndReturnResult(
                                                         Command.<Greet>builder().body(new Greet("x")).build(),
                                                         ExecutionContext.root(),
                                                         ErrorPolicy.failFast());
                var                    f = assertInstanceOf(DispatchResult.Failure.class, r);
                assertInstanceOf(DispatchTestFixtures.InvalidSku.class, f.cause());
                assertEquals("bad:x", f.cause().getMessage());
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }

    // Technical error: wrapped and carries context with zero interceptors.
    @Test
    void technicalError_isWrappedAndCarriesContext_zeroInterceptors() {
        ExecutionContext ctx = ExecutionContext.root();
        try (FlowRuntime runtime = newRuntimeWithZeroInterceptors()) {
            var handler =
                    new AbstractReturnCommandHandler<Greet, String>() {
                        @Override
                        protected String handle(Greet command) {
                            throw new IllegalStateException("DB down");
                        }
                    };
            runtime.commands().register(handler);
            try {
                DispatchResult<String> r    =
                        runtime
                                .commands()
                                .dispatchAndReturnResult(
                                                         Command.<Greet>builder().body(new Greet("x")).build(),
                                                         ctx,
                                                         ErrorPolicy.failFast());
                var                    f    = assertInstanceOf(DispatchResult.Failure.class, r);
                FlowError.Technical    tech = assertInstanceOf(FlowError.Technical.class, f.cause());
                assertSame(ctx, tech.executionContext());
                assertInstanceOf(IllegalStateException.class, tech.getCause());
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }

    // Cancellation surfaces unchanged with zero interceptors.
    @Test
    void cancellation_surfacesFlowCancellationException_zeroInterceptors() {
        try (FlowRuntime runtime = newRuntimeWithZeroInterceptors()) {
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
                ctx.cancellation().cancel();
                DispatchResult<String> r =
                        runtime
                                .commands()
                                .dispatchAndReturnResult(
                                                         Command.<Greet>builder().body(new Greet("x")).build(),
                                                         ctx,
                                                         ErrorPolicy.failFast());
                var                    f = assertInstanceOf(DispatchResult.Failure.class, r);
                assertInstanceOf(FlowCancellationException.class, f.cause());
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }

    // Deadline surfaces unchanged with zero interceptors.
    @Test
    void deadlineExceeded_surfacesFlowDeadlineExceededException_zeroInterceptors() {
        try (FlowRuntime runtime = newRuntimeWithZeroInterceptors()) {
            var handler =
                    new AbstractReturnCommandHandler<Greet, String>() {
                        @Override
                        protected String handle(Greet command) {
                            return "ok";
                        }
                    };
            runtime.commands().register(handler);
            try {
                Instant                       deadline = Instant.parse("2026-05-10T09:59:00Z");
                ExecutionContext              ctx      =
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
                DispatchResult<String>        r        =
                        runtime
                                .commands()
                                .dispatchAndReturnResult(
                                                         Command.<Greet>builder().body(new Greet("x")).build(),
                                                         ctx,
                                                         ErrorPolicy.failFast());
                var                           f        = assertInstanceOf(DispatchResult.Failure.class, r);
                FlowDeadlineExceededException dl       =
                        assertInstanceOf(FlowDeadlineExceededException.class, f.cause());
                assertEquals(deadline, dl.deadline());
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }

    // CollectFailures aggregates across listeners, zero interceptors path.
    @Test
    void collectFailures_returnsPartialFailureWithAllCauses_zeroInterceptors() {
        try (FlowRuntime runtime = newRuntimeWithZeroInterceptors()) {
            EventBus      bus = runtime.events();
            AtomicInteger ok  = new AtomicInteger();
            var           lA  =
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
            var           lB  =
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
            var           lC  =
                    new AbstractDomainEventListener<FanEvent>() {
                                          @Override
                                          public void handle(FanEvent event) {
                                              ok.incrementAndGet();
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
                                           new FanEvent("agg-zero"), ExecutionContext.root(), ErrorPolicy.collectFailures());
                if (!(r instanceof DispatchResult.PartialFailure<Void> p)) {
                    fail("expected PartialFailure, got " + r);
                    return;
                }
                assertEquals(2, p.failures().size());
                List<String> messages = p.failures().stream().map(Throwable::getMessage).sorted().toList();
                assertEquals(List.of("A", "B"), messages);
                assertEquals(1, ok.get(), "surviving listener must still have executed");
            } finally {
                bus.unregister(lA);
                bus.unregister(lB);
                bus.unregister(lC);
            }
        }
    }
}
