package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.cqrs.event.exceptions.EventPublishRejectedException;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.junit.jupiter.api.Test;

/**
 * Tests for EventListener cross-cutting enhancements wired by {@link ListenerExecutor} and {@link
 * DefaultEventBus}: filter, retry, errorHandler, concurrencyLevel, publish backpressure, and stats
 * counters.
 */
class EventListenerContractTest {

    static final class TestEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        private final String value;

        TestEvent(String value) {
            super("agg-1");
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    private static FlowRuntime newRuntime() {
        return FlowRuntime.builder().build();
    }

    private static EventBus busOf(FlowRuntime rt) {
        return rt.events();
    }

    private static ErrorPolicy ignoreAllFailures() {
        return ErrorPolicy.ignore(__ -> true);
    }

    /** Filter returning false skips handler invocation and increments filtered stat. */
    @Test
    void filter_returning_false_skips_handle_and_increments_filtered_stat() {
        try (var rt = newRuntime()) {
            EventBus      bus         = busOf(rt);
            AtomicInteger handleCount = new AtomicInteger();

            var listener =
                    new AbstractDomainEventListener<TestEvent>() {
                        @Override
                        public boolean filter(TestEvent event) {
                            return false;
                        }

                        @Override
                        public void handle(TestEvent event) {
                            handleCount.incrementAndGet();
                        }
                    };
            bus.register(listener);

            bus.dispatchResult(new TestEvent("x"), ExecutionContext.root(), ErrorPolicy.failFast());

            assertEquals(0, handleCount.get(), "handle must NOT be called when filter returns false");
            assertEquals(
                         1,
                         bus.stats().forListener(listener.getClass()).filtered(),
                         "filtered counter must be incremented");
        }
    }

    /** Filter returning true allows handler to process the event. */
    @Test
    void filter_returning_true_allows_handle_to_proceed() {
        try (var rt = newRuntime()) {
            EventBus      bus         = busOf(rt);
            AtomicInteger handleCount = new AtomicInteger();

            var listener =
                    new AbstractDomainEventListener<TestEvent>() {
                        @Override
                        public boolean filter(TestEvent event) {
                            return "yes".equals(event.value());
                        }

                        @Override
                        public void handle(TestEvent event) {
                            handleCount.incrementAndGet();
                        }
                    };
            bus.register(listener);

            bus.dispatchResult(new TestEvent("yes"), ExecutionContext.root(), ErrorPolicy.failFast());
            bus.dispatchResult(new TestEvent("no"), ExecutionContext.root(), ErrorPolicy.failFast());

            assertEquals(1, handleCount.get(), "only the accepted event should reach handle");
            assertEquals(1, bus.stats().forListener(listener.getClass()).filtered());
            assertEquals(1, bus.stats().forListener(listener.getClass()).successes());
        }
    }

    /** Retry policy respects max attempt count before propagating exception. */
    @Test
    void retry_fixed_delay_retries_exactly_maxAttempts_times_then_propagates() {
        try (var rt = newRuntime()) {
            EventBus      bus      = busOf(rt);
            AtomicInteger attempts = new AtomicInteger();

            var listener =
                    new AbstractDomainEventListener<TestEvent>() {
                        @Override
                        public RetryPolicy retryPolicy() {
                            return new RetryPolicy.FixedDelay(3, Duration.ZERO);
                        }

                        @Override
                        public void handle(TestEvent event) {
                            attempts.incrementAndGet();
                            throw new RuntimeException("always fail");
                        }
                    };
            bus.register(listener);

            bus.dispatchResult(new TestEvent("x"), ExecutionContext.root(), ignoreAllFailures());

            assertEquals(3, attempts.get(), "must attempt exactly 3 times");
            assertEquals(1, bus.stats().forListener(listener.getClass()).errors());
        }
    }

    /** Successful retry counts as success and stops retrying. */
    @Test
    void retry_succeeds_on_second_attempt_counts_as_success() {
        try (var rt = newRuntime()) {
            EventBus      bus      = busOf(rt);
            AtomicInteger attempts = new AtomicInteger();

            var listener =
                    new AbstractDomainEventListener<TestEvent>() {
                        @Override
                        public RetryPolicy retryPolicy() {
                            return new RetryPolicy.FixedDelay(3, Duration.ZERO);
                        }

                        @Override
                        public void handle(TestEvent event) {
                            if (attempts.incrementAndGet() < 2) {
                                throw new RuntimeException("fail once");
                            }
                        }
                    };
            bus.register(listener);

            DispatchResult<Void> result =
                    bus.dispatchResult(new TestEvent("x"), ExecutionContext.root(), ErrorPolicy.failFast());

            assertInstanceOf(DispatchResult.Success.class, result);
            assertEquals(2, attempts.get());
            assertEquals(1, bus.stats().forListener(listener.getClass()).successes());
            assertEquals(0, bus.stats().forListener(listener.getClass()).errors());
        }
    }

    /** Error handler may swallow exceptions and prevent bus-level error reporting. */
    @Test
    void error_handler_swallows_exception_after_retries_exhausted() {
        try (var rt = newRuntime()) {
            EventBus                   bus      = busOf(rt);
            AtomicReference<Throwable> captured = new AtomicReference<>();

            var listener =
                    new AbstractDomainEventListener<TestEvent>() {
                        @Override
                        public RetryPolicy retryPolicy() {
                            return new RetryPolicy.FixedDelay(2, Duration.ZERO);
                        }

                        @Override
                        public EventListenerErrorHandler<TestEvent> errorHandler() {
                            return (event, cause) -> captured.set(cause);
                        }

                        @Override
                        public void handle(TestEvent event) {
                            throw new IllegalStateException("boom");
                        }
                    };
            bus.register(listener);

            DispatchResult<Void> result =
                    bus.dispatchResult(new TestEvent("x"), ExecutionContext.root(), ErrorPolicy.failFast());

            assertInstanceOf(
                             DispatchResult.Success.class,
                             result,
                             "errorHandler swallowed — should be success at bus level");
            assertNotNull(captured.get(), "error handler must have been called");
            assertInstanceOf(IllegalStateException.class, captured.get());
        }
    }

    /** Error handler rethrows propagate to bus-level error policy. */
    @Test
    void error_handler_rethrow_propagates_to_bus_error_policy() {
        try (var rt = newRuntime()) {
            EventBus bus = busOf(rt);

            var listener =
                    new AbstractDomainEventListener<TestEvent>() {
                        @Override
                        public EventListenerErrorHandler<TestEvent> errorHandler() {
                            return (event, cause) -> {
                                throw new RuntimeException("rethrown", cause);
                            };
                        }

                        @Override
                        public void handle(TestEvent event) {
                            throw new IllegalStateException("original");
                        }
                    };
            bus.register(listener);

            bus.dispatchResult(new TestEvent("x"), ExecutionContext.root(), ignoreAllFailures());

            assertEquals(1, bus.stats().forListener(listener.getClass()).errors());
        }
    }

    /** Dead-letter queue captures events after retries and error handler processing. */
    @Test
    void dead_letter_queue_receives_entry_after_all_retries_and_no_error_handler() {
        var dlq = new InMemoryDeadLetterQueue();
        try (var rt = newRuntime()) {
            EventBus bus = busOf(rt);
            bus.deadLetterQueue(dlq);

            var listener =
                    new AbstractDomainEventListener<TestEvent>() {
                        @Override
                        public RetryPolicy retryPolicy() {
                            return new RetryPolicy.FixedDelay(2, Duration.ZERO);
                        }

                        @Override
                        public void handle(TestEvent event) {
                            throw new RuntimeException("always fail");
                        }
                    };
            bus.register(listener);

            DispatchResult<Void> result =
                    bus.dispatchResult(new TestEvent("x"), ExecutionContext.root(), ErrorPolicy.failFast());

            assertInstanceOf(
                             DispatchResult.Success.class, result, "DLQ absorbed the failure — bus must see success");
            assertEquals(1, dlq.size());
            DeadLetterEntry entry = dlq.drain().getFirst();
            assertEquals(2, entry.totalAttempts());
            assertEquals(listener.getClass(), entry.listenerClass());
            assertEquals(1, bus.stats().forListener(listener.getClass()).deadLettered());
        }
    }

    /** Concurrency level 1 serializes parallel invocations for a single listener. */
    @Test
    void concurrency_level_1_serializes_parallel_invocations() throws InterruptedException {
        try (var rt = FlowRuntime.builder().parallelListeners(true).build()) {
            EventBus       bus           = busOf(rt);
            AtomicInteger  concurrent    = new AtomicInteger();
            AtomicInteger  maxConcurrent = new AtomicInteger();
            CountDownLatch allDone       = new CountDownLatch(5);

            var listener =
                    new AbstractDomainEventListener<TestEvent>() {
                        @Override
                        public boolean parallelSafe() {
                            return true;
                        }

                        @Override
                        public int concurrencyLevel() {
                            return 1;
                        }

                        @Override
                        public void handle(TestEvent event) {
                            int c = concurrent.incrementAndGet();
                            maxConcurrent.accumulateAndGet(c, Math::max);
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                            concurrent.decrementAndGet();
                            allDone.countDown();
                        }
                    };
            bus.register(listener);

            var executor = Executors.newFixedThreadPool(5);
            for (int i = 0; i < 5; i++) {
                executor.submit(
                                () -> bus.dispatchResult(
                                                         new TestEvent("x"), ExecutionContext.root(), ErrorPolicy.failFast()));
            }
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(allDone.await(5, TimeUnit.SECONDS));

            assertEquals(
                         1, maxConcurrent.get(), "concurrencyLevel=1 must serialize: max concurrent should be 1");
        }
    }

    /** Publish backpressure with DROP policy returns success without invoking listener. */
    @Test
    void publish_backpressure_drop_returns_success_without_invoking_listener() throws InterruptedException {
        try (var rt = newRuntime()) {
            EventBus bus = busOf(rt);
            bus.publishBackpressure(
                                    EventPublishBackpressureSettings.of(1, EventPublishSaturationPolicy.DROP));

            AtomicInteger  handleCount  = new AtomicInteger();
            CountDownLatch holdFirst    = new CountDownLatch(1);
            CountDownLatch firstStarted = new CountDownLatch(1);

            var listener =
                    new AbstractDomainEventListener<TestEvent>() {
                        @Override
                        public void handle(TestEvent event) {
                            firstStarted.countDown();
                            try {
                                holdFirst.await(3, TimeUnit.SECONDS);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                            handleCount.incrementAndGet();
                        }
                    };
            bus.register(listener);

            var thread =
                    new Thread(
                            () -> bus.dispatchResult(
                                                     new TestEvent("1"), ExecutionContext.root(), ErrorPolicy.failFast()));
            thread.start();

            assertTrue(firstStarted.await(3, TimeUnit.SECONDS));

            DispatchResult<Void> dropped =
                    bus.dispatchResult(new TestEvent("2"), ExecutionContext.root(), ErrorPolicy.failFast());
            assertInstanceOf(
                             DispatchResult.Success.class,
                             dropped,
                             "DROP policy must return success without invoking listener");

            holdFirst.countDown();
            try {
                thread.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(e);
            }

            assertEquals(1, handleCount.get(), "second event must have been dropped");
        }
    }

    /** Publish backpressure with REJECT policy throws exception when queue is full. */
    @Test
    void publish_backpressure_reject_throws_EventPublishRejectedException() throws InterruptedException {
        try (var rt = newRuntime()) {
            EventBus bus = busOf(rt);
            bus.publishBackpressure(
                                    EventPublishBackpressureSettings.of(1, EventPublishSaturationPolicy.REJECT));

            CountDownLatch holdFirst    = new CountDownLatch(1);
            CountDownLatch firstStarted = new CountDownLatch(1);

            var listener =
                    new AbstractDomainEventListener<TestEvent>() {
                        @Override
                        public void handle(TestEvent event) {
                            firstStarted.countDown();
                            try {
                                holdFirst.await(3, TimeUnit.SECONDS);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    };
            bus.register(listener);

            var thread =
                    new Thread(
                            () -> bus.dispatchResult(
                                                     new TestEvent("1"), ExecutionContext.root(), ErrorPolicy.failFast()));
            thread.start();
            assertTrue(firstStarted.await(3, TimeUnit.SECONDS));

            assertThrows(
                         EventPublishRejectedException.class,
                         () -> bus.dispatchResult(
                                                  new TestEvent("2"), ExecutionContext.root(), ErrorPolicy.failFast()),
                         "REJECT policy must throw EventPublishRejectedException");

            holdFirst.countDown();
            try {
                thread.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(e);
            }
        }
    }

    /** Stats accurately track successes, errors, and total invocations. */
    @Test
    void stats_track_successes_errors_and_invocations_accurately() {
        try (var rt = newRuntime()) {
            EventBus      bus       = busOf(rt);
            AtomicInteger callCount = new AtomicInteger();

            var listener =
                    new AbstractDomainEventListener<TestEvent>() {
                        @Override
                        public void handle(TestEvent event) {
                            if ("fail".equals(event.value())) {
                                throw new RuntimeException("fail");
                            }
                            callCount.incrementAndGet();
                        }
                    };
            bus.register(listener);

            bus.dispatchResult(new TestEvent("ok"), ExecutionContext.root(), ignoreAllFailures());
            bus.dispatchResult(new TestEvent("ok"), ExecutionContext.root(), ignoreAllFailures());
            bus.dispatchResult(new TestEvent("fail"), ExecutionContext.root(), ignoreAllFailures());

            ListenerStats s = bus.stats().forListener(listener.getClass());
            assertNotNull(s);
            assertEquals(3, s.invocations());
            assertEquals(2, s.successes());
            assertEquals(1, s.errors());
            assertEquals(2, callCount.get());
        }
    }
}
