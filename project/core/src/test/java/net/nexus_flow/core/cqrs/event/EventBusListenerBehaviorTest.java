package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.cqrs.query.AbstractQueryHandler;
import net.nexus_flow.core.cqrs.query.Query;
import net.nexus_flow.core.cqrs.query.QueryBus;
import net.nexus_flow.core.cqrs.query.QuerySettings;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link EventBus} listener lifecycle features: retry policies, error handlers,
 * deduplication, filtering, rate limiting, pause/resume, stats counters, and query handler bulkhead
 * concurrency control.
 */
class EventBusListenerBehaviorTest {

    record SlowBulkheadQuery(String value) {
    }

    static class ObservedEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        private final int amount;

        ObservedEvent(String aggregateId, int amount) {
            super(aggregateId);
            this.amount = amount;
        }

        int amount() {
            return amount;
        }
    }

    static final class DedupEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        private final String key;

        DedupEvent(String aggregateId, String key) {
            super(aggregateId);
            this.key = key;
        }

        @Override
        public String idempotencyKey() {
            return key;
        }
    }

    static final class CountingObservedListener extends AbstractDomainEventListener<ObservedEvent> {
        private final AtomicInteger handled;

        CountingObservedListener(AtomicInteger handled) {
            this.handled = handled;
        }

        @Override
        public void handle(ObservedEvent event) {
            handled.incrementAndGet();
        }
    }

    /** Dead-letter queue records failed events after all retry attempts are exhausted. */
    @Test
    void dead_letter_queue_receives_entry_when_retries_are_exhausted() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var bus      = runtime.events();
            var dlq      = new InMemoryDeadLetterQueue();
            var attempts = new AtomicInteger();
            var listener =
                    new AbstractDomainEventListener<ObservedEvent>() {
                                     @Override
                                     public RetryPolicy retryPolicy() {
                                         return new RetryPolicy.FixedDelay(2, Duration.ZERO);
                                     }

                                     @Override
                                     public void handle(ObservedEvent event) {
                                         attempts.incrementAndGet();
                                         throw new IllegalStateException("boom");
                                     }
                                 };
            bus.deadLetterQueue(dlq);
            bus.register(listener);
            try {
                var result = bus.dispatchResult(new ObservedEvent("agg-1", 10));

                assertInstanceOf(net.nexus_flow.core.runtime.result.DispatchResult.Success.class, result);
                assertEquals(2, attempts.get());
                assertEquals(1, dlq.size());

                DeadLetterEntry entry = dlq.drain().getFirst();
                assertEquals(listener.getClass(), entry.listenerClass());
                assertEquals("boom", entry.cause().getMessage());
                assertEquals(2, entry.totalAttempts());
                assertNotNull(entry.occurredAt());

                ListenerStats stats = bus.stats().forListener(listener.getClass());
                assertNotNull(stats);
                assertEquals(1, stats.errors());
                assertEquals(1, stats.deadLettered());
            } finally {
                bus.unregister(listener);
                bus.deadLetterQueue(null);
            }
        }
    }

    /** Paused listeners skip event dispatch and increment filtered counter. */
    @Test
    void paused_listener_skips_event() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var bus      = runtime.events();
            var handled  = new AtomicInteger();
            var listener =
                    new AbstractDomainEventListener<ObservedEvent>() {
                                     @Override
                                     public void handle(ObservedEvent event) {
                                         handled.incrementAndGet();
                                     }
                                 };
            bus.register(listener);
            try {
                bus.pause(listener);
                bus.dispatchResult(new ObservedEvent("agg-1", 1));

                assertTrue(bus.isPaused(listener));
                assertEquals(0, handled.get());
                assertEquals(1, bus.stats().forListener(listener.getClass()).filtered());

                bus.resume(listener);
                bus.dispatchResult(new ObservedEvent("agg-1", 2));

                assertFalse(bus.isPaused(listener));
                assertEquals(1, handled.get());
            } finally {
                bus.unregister(listener);
            }
        }
    }

    /** Stats track invocations, filtered events, and successful handlers. */
    @Test
    void stats_record_invocations_and_filtered_counts() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var bus      = runtime.events();
            var handled  = new AtomicInteger();
            var listener =
                    new AbstractDomainEventListener<ObservedEvent>() {
                                     @Override
                                     public boolean filter(ObservedEvent event) {
                                         return event.amount() >= 10;
                                     }

                                     @Override
                                     public void handle(ObservedEvent event) {
                                         handled.incrementAndGet();
                                     }
                                 };
            bus.register(listener);
            try {
                bus.dispatchResult(new ObservedEvent("agg-1", 5));

                ListenerStats stats = bus.stats().forListener(listener.getClass());
                assertNotNull(stats);
                assertEquals(1, stats.invocations());
                assertEquals(1, stats.filtered());
                assertEquals(0, stats.successes());
                assertEquals(0, handled.get());
            } finally {
                bus.unregister(listener);
            }
        }
    }

    /** Rate limits enforce max concurrent invocations per listener. */
    @Test
    void rate_limit_drops_excess_events() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var bus      = runtime.events();
            var handled  = new AtomicInteger();
            var listener =
                    new AbstractDomainEventListener<ObservedEvent>() {
                                     @Override
                                     public ListenerRateLimit rateLimit() {
                                         return ListenerRateLimit.of(1);
                                     }

                                     @Override
                                     public void handle(ObservedEvent event) {
                                         handled.incrementAndGet();
                                     }
                                 };
            bus.register(listener);
            try {
                bus.dispatchResult(new ObservedEvent("agg-1", 1));
                bus.dispatchResult(new ObservedEvent("agg-1", 2));

                ListenerStats stats = bus.stats().forListener(listener.getClass());
                assertNotNull(stats);
                assertEquals(2, stats.invocations());
                assertEquals(1, stats.successes());
                assertEquals(1, stats.rateLimited());
                assertEquals(1, handled.get());
            } finally {
                bus.unregister(listener);
            }
        }
    }

    /** Deduplication skips events with duplicate idempotency keys. */
    @Test
    void deduplication_skips_duplicate_event() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var bus      = runtime.events();
            var handled  = new AtomicInteger();
            var listener =
                    new AbstractDomainEventListener<DedupEvent>() {
                                     @Override
                                     public boolean deduplicateEnabled() {
                                         return true;
                                     }

                                     @Override
                                     public void handle(DedupEvent event) {
                                         handled.incrementAndGet();
                                     }
                                 };
            bus.register(listener);
            try {
                bus.dispatchResult(new DedupEvent("agg-1", "key-1"));
                bus.dispatchResult(new DedupEvent("agg-1", "key-1"));

                ListenerStats stats = bus.stats().forListener(listener.getClass());
                assertNotNull(stats);
                assertEquals(2, stats.invocations());
                assertEquals(1, stats.successes());
                assertEquals(1, stats.deduplicated());
                assertEquals(1, handled.get());
            } finally {
                bus.unregister(listener);
            }
        }
    }

    /** Stats aggregate listeners of the same class and snapshots stay stable after capture. */
    @Test
    void stats_aggregate_same_listener_class_and_return_snapshot() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var bus     = runtime.events();
            var handled = new AtomicInteger();
            var first   = new CountingObservedListener(handled);
            var second  = new CountingObservedListener(handled);
            bus.register(first);
            bus.register(second);
            try {
                bus.dispatchResult(new ObservedEvent("agg-1", 1));

                EventBusStats snapshot = bus.stats();
                ListenerStats captured = snapshot.forListener(CountingObservedListener.class);
                assertNotNull(captured);
                assertEquals(2, captured.invocations());
                assertEquals(2, captured.successes());
                assertEquals(2, handled.get());

                bus.dispatchResult(new ObservedEvent("agg-1", 2));

                assertEquals(2, captured.successes(), "snapshot must not change after it is captured");
                ListenerStats current = bus.stats().forListener(CountingObservedListener.class);
                assertNotNull(current);
                assertEquals(4, current.invocations());
                assertEquals(4, current.successes());
            } finally {
                bus.unregister(first);
                bus.unregister(second);
            }
        }
    }

    /** Concurrent duplicate dispatches share one in-flight idempotency claim. */
    @Test
    void deduplication_skips_concurrent_duplicate_event() throws InterruptedException, ExecutionException, TimeoutException {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var            bus          = runtime.events();
            var            handled      = new AtomicInteger();
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicBoolean  firstCall    = new AtomicBoolean(true);
            var            listener     =
                    new AbstractDomainEventListener<DedupEvent>() {
                                                    @Override
                                                    public boolean deduplicateEnabled() {
                                                        return true;
                                                    }

                                                    @Override
                                                    public void handle(DedupEvent event) {
                                                        handled.incrementAndGet();
                                                        if (firstCall.compareAndSet(true, false)) {
                                                            firstEntered.countDown();
                                                            try {
                                                                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                                                            } catch (InterruptedException e) {
                                                                Thread.currentThread().interrupt();
                                                                throw new RuntimeException(e);
                                                            }
                                                        }
                                                    }
                                                };
            bus.register(listener);
            ExecutorService executor = Executors.newFixedThreadPool(1);
            try {
                Future<?> first =
                        executor.submit(() -> bus.dispatchResult(new DedupEvent("agg-1", "key-1")));
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));

                bus.dispatchResult(new DedupEvent("agg-1", "key-1"));

                releaseFirst.countDown();
                first.get(5, TimeUnit.SECONDS);

                ListenerStats stats = bus.stats().forListener(listener.getClass());
                assertNotNull(stats);
                assertEquals(2, stats.invocations());
                assertEquals(1, stats.successes());
                assertEquals(1, stats.deduplicated());
                assertEquals(1, handled.get());
            } finally {
                releaseFirst.countDown();
                executor.shutdownNow();
                bus.unregister(listener);
            }
        }
    }

    /** Query handler bulkhead concurrency level restricts parallel invocations. */
    @Test
    void query_bulkhead_limits_concurrency() throws InterruptedException, ExecutionException, TimeoutException {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            QueryBus       queryBus      = runtime.queries();
            CountDownLatch firstEntered  = new CountDownLatch(1);
            CountDownLatch releaseFirst  = new CountDownLatch(1);
            CountDownLatch secondEntered = new CountDownLatch(1);
            AtomicBoolean  firstCall     = new AtomicBoolean(true);
            AtomicInteger  active        = new AtomicInteger();
            AtomicInteger  maxActive     = new AtomicInteger();
            var            handler       =
                    new AbstractQueryHandler<SlowBulkheadQuery, String>() {
                                                     @Override
                                                     public QuerySettings settings() {
                                                         return QuerySettings.withMaxConcurrent(1);
                                                     }

                                                     @Override
                                                     public String handle(SlowBulkheadQuery query) {
                                                         int current = active.incrementAndGet();
                                                         maxActive.accumulateAndGet(current, Math::max);
                                                         try {
                                                             if (firstCall.compareAndSet(true, false)) {
                                                                 firstEntered.countDown();
                                                                 try {
                                                                     assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                                                                 } catch (InterruptedException e) {
                                                                     Thread.currentThread().interrupt();
                                                                     throw new RuntimeException(e);
                                                                 }
                                                             } else {
                                                                 secondEntered.countDown();
                                                             }
                                                             return query.value();
                                                         } finally {
                                                             active.decrementAndGet();
                                                         }
                                                     }
                                                 };
            queryBus.register(handler);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<String> first =
                        executor.submit(
                                        () -> queryBus.ask(
                                                           Query.<SlowBulkheadQuery>builder()
                                                                   .body(new SlowBulkheadQuery("first"))
                                                                   .build()));
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));

                Future<String> second =
                        executor.submit(
                                        () -> queryBus.ask(
                                                           Query.<SlowBulkheadQuery>builder()
                                                                   .body(new SlowBulkheadQuery("second"))
                                                                   .build()));

                assertFalse(secondEntered.await(200, TimeUnit.MILLISECONDS));
                releaseFirst.countDown();

                assertEquals("first", first.get(5, TimeUnit.SECONDS));
                assertEquals("second", second.get(5, TimeUnit.SECONDS));
                assertEquals(1, maxActive.get());
            } finally {
                executor.shutdownNow();
                queryBus.unregister(handler);
            }
        }
    }
}
