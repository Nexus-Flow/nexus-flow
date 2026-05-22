package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.observability.MetricsRecorder;
import net.nexus_flow.core.observability.Observability;
import net.nexus_flow.core.observability.TracingBridge;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * proves that {@link MetricsRecorder} and {@link TracingBridge} sinks injected via {@link
 * FlowRuntime.Builder#observability(Observability)} actually receive listener-level signals
 * (invocations, errors, retries, dead-letter, success) from {@link ListenerExecutor}.
 *
 * <p>This is the integration proof that adapter modules (Micrometer / OpenTelemetry / Datadog) can
 * hook into the framework without depending on those libraries from {@code core}.
 */
class ObservabilitySpiWiringTest {

    public static final class BoomEvent extends AbstractDomainEvent {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        BoomEvent() {
            super("agg-1");
        }
    }

    public static final class OkEvent extends AbstractDomainEvent {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        OkEvent() {
            super("agg-2");
        }
    }

    public static final class BoomListener extends AbstractDomainEventListener<BoomEvent> {
        @Override
        public RetryPolicy retryPolicy() {
            return new RetryPolicy.FixedDelay(2, Duration.ZERO);
        }

        @Override
        public void handle(BoomEvent event) {
            throw new IllegalStateException("boom");
        }
    }

    public static final class OkListener extends AbstractDomainEventListener<OkEvent> {
        @Override
        public void handle(OkEvent event) {
            /* no-op */
        }
    }

    /**
     * In-memory metrics sink that counts every signal — what a Micrometer adapter would do, minus the
     * registry.
     */
    static final class RecordingMetrics implements MetricsRecorder {
        final ConcurrentHashMap<String, AtomicLong> counters   = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, AtomicLong> timerCount = new ConcurrentHashMap<>();

        @Override
        public void incrementCounter(String name, Map<String, String> tags) {
            counters.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
        }

        @Override
        public void recordTimer(String name, Duration duration, Map<String, String> tags) {
            timerCount.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
        }

        @Override
        public void recordGauge(String name, long value, Map<String, String> tags) {
            /* unused */
        }

        long count(String name) {
            return counters.getOrDefault(name, new AtomicLong()).get();
        }
    }

    /** In-memory tracing sink that counts span open/close — what an OTel adapter would do. */
    static final class RecordingTracing implements TracingBridge {
        final AtomicInteger spansOpened = new AtomicInteger();
        final AtomicInteger spansClosed = new AtomicInteger();

        @Override
        public Span startSpan(String operationName, Map<String, String> attributes) {
            spansOpened.incrementAndGet();
            return spansClosed::incrementAndGet;
        }
    }

    @Test
    void metricsAndTracing_receiveListenerSignals_endToEnd() {
        RecordingMetrics metrics = new RecordingMetrics();
        RecordingTracing tracing = new RecordingTracing();

        try (FlowRuntime rt =
                FlowRuntime.builder().observability(new Observability(metrics, tracing)).build()) {

            EventBus bus = rt.events();
            bus.deadLetterQueue(new InMemoryDeadLetterQueue(16));

            bus.register(new OkListener());
            bus.register(new BoomListener());

            bus.dispatchResult(new OkEvent(), ExecutionContext.root(), ErrorPolicy.failFast());
            bus.dispatchResult(new BoomEvent(), ExecutionContext.root(), ErrorPolicy.failFast());

            // OkListener: 1 invocation + 1 success
            assertEquals(2, metrics.count("nexus.flow.listener.invocations"));
            assertEquals(1, metrics.count("nexus.flow.listener.success"));
            // BoomListener: 2 attempts (retries metric) + 1 dead-letter + 1 error
            assertEquals(2, metrics.count("nexus.flow.listener.retries"));
            assertEquals(1, metrics.count("nexus.flow.listener.dead_lettered"));
            assertEquals(1, metrics.count("nexus.flow.listener.errors"));
            // Timer fired once per dispatch
            assertTrue(
                       metrics.timerCount.getOrDefault("nexus.flow.listener.duration", new AtomicLong()).get() >= 2);
            // Tracing: 2 spans (one per dispatch), all closed
            assertEquals(2, tracing.spansOpened.get());
            assertEquals(2, tracing.spansClosed.get());
        }
    }
}
