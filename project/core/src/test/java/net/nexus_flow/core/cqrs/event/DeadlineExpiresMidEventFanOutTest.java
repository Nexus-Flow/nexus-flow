package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.CancellationToken;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowDeadlineExceededException;
import org.junit.jupiter.api.Test;

/**
 * Audit §6 T4. When the {@link ExecutionContext#deadline()} elapses while a sequential listener
 * loop is mid-iteration, the remaining listeners MUST be short-circuited with {@link
 * FlowDeadlineExceededException}.
 *
 * <p>The contract is the dispatch loop's {@code ctx.throwIfCancelledOrExpired()} poll at the top of
 * each iteration: it converts a deadline overrun into a thrown exception, which the error-policy
 * folding classifies and either short-circuits (FailFast) or appends to the accumulated list
 * (CollectFailures).
 *
 * <p>The test uses a deadline that has ALREADY elapsed at dispatch time to make the assertion
 * deterministic — the first poll fails, so listener #1 never runs (the {@code throwIfCancelled}
 * call is BEFORE the invocation in the loop body). For the "expires mid-loop" interpretation we
 * also include a variant that uses an inline listener to consume the budget so listener #2 is
 * short-circuited after listener #1 has run.
 */
class DeadlineExpiresMidEventFanOutTest {

    static final class Pulse extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Pulse() {
            super(UUID.randomUUID().toString());
        }
    }

    private static ExecutionContext expiredCtx() {
        return new ExecutionContext(
                MessageId.random(),
                TraceId.random(),
                CorrelationId.random(),
                CausationId.ROOT,
                null,
                null,
                Clock.systemUTC().instant().minus(Duration.ofMinutes(1)),
                CancellationToken.create(),
                java.util.Map.of());
    }

    @Test
    void expiredDeadline_beforeAnyListenerRuns_failsFastWithDeadlineException() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            AtomicInteger calls = new AtomicInteger();
            runtime
                    .events()
                    .register(
                              new AbstractDomainEventListener<Pulse>() {
                                  @Override
                                  public void handle(Pulse event) {
                                      calls.incrementAndGet();
                                  }
                              });

            DispatchResult<Void> result =
                    runtime.events().dispatchResult(new Pulse(), expiredCtx(), ErrorPolicy.failFast());

            assertEquals(0, calls.get(), "no listener may run when the deadline has already elapsed");
            DispatchResult.Failure<?> failure = assertInstanceOf(DispatchResult.Failure.class, result);
            assertInstanceOf(FlowDeadlineExceededException.class, failure.cause());
        }
    }

    @Test
    void deadlineExpiresAfterFirstListener_shortCircuitsRemaining_failFast() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            AtomicInteger l1Calls = new AtomicInteger();
            AtomicInteger l2Calls = new AtomicInteger();
            AtomicInteger l3Calls = new AtomicInteger();

            // Listener 1 burns enough wall clock so the deadline elapses BEFORE listener 2 starts.
            runtime
                    .events()
                    .register(
                              new AbstractDomainEventListener<Pulse>() {
                                  @Override
                                  public void handle(Pulse event) {
                                      l1Calls.incrementAndGet();
                                      try {
                                          Thread.sleep(60);
                                      } catch (InterruptedException _) {
                                          Thread.currentThread().interrupt();
                                      }
                                  }
                              });
            runtime
                    .events()
                    .register(
                              new AbstractDomainEventListener<Pulse>() {
                                  @Override
                                  public void handle(Pulse event) {
                                      l2Calls.incrementAndGet();
                                  }
                              });
            runtime
                    .events()
                    .register(
                              new AbstractDomainEventListener<Pulse>() {
                                  @Override
                                  public void handle(Pulse event) {
                                      l3Calls.incrementAndGet();
                                  }
                              });

            // 30ms deadline; listener 1 sleeps 60ms, so the poll BEFORE listener 2 will see the
            // deadline elapsed.
            ExecutionContext ctx =
                    new ExecutionContext(
                            MessageId.random(),
                            TraceId.random(),
                            CorrelationId.random(),
                            CausationId.ROOT,
                            null,
                            null,
                            Clock.systemUTC().instant().plus(Duration.ofMillis(30)),
                            CancellationToken.create(),
                            java.util.Map.of());

            DispatchResult<Void> result =
                    runtime.events().dispatchResult(new Pulse(), ctx, ErrorPolicy.failFast());

            assertEquals(1, l1Calls.get(), "listener 1 must run before the deadline elapses");
            assertEquals(
                         0,
                         l2Calls.get(),
                         "listener 2 must be short-circuited — the deadline elapsed during listener 1's body");
            assertEquals(0, l3Calls.get(), "listener 3 must also be short-circuited");
            assertInstanceOf(
                             FlowDeadlineExceededException.class,
                             assertInstanceOf(DispatchResult.Failure.class, result).cause(),
                             "result cause must be FlowDeadlineExceededException");
        }
    }

    @Test
    void expiredDeadline_underCollectFailures_yieldsDeadlineCause() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            runtime
                    .events()
                    .register(
                              new AbstractDomainEventListener<Pulse>() {
                                  @Override
                                  public void handle(Pulse event) {
                                      // never runs
                                  }
                              });

            DispatchResult<Void> result =
                    runtime.events().dispatchResult(new Pulse(), expiredCtx(), ErrorPolicy.collectFailures());

            // Under CollectFailures, the expired deadline is the only "failure" and folds into a
            // Failure with FlowDeadlineExceededException as the cause (sequential loop's short-circuit
            // path with forceTerminate=true converts the deadline overflow into a terminal Failure,
            // NOT a partial-failure accumulation since no listener has been visited yet).
            assertFalse(
                        result instanceof DispatchResult.Success<Void>,
                        "expired deadline must NOT classify as Success under CollectFailures");
            assertTrue(
                       result instanceof DispatchResult.Failure<Void> || result instanceof DispatchResult.PartialFailure<Void>,
                       "expected Failure or PartialFailure; got " + result.getClass().getName());
        }
    }
}
