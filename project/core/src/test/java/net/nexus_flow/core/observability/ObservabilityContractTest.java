package net.nexus_flow.core.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Observability} entry point for contract compliance: non-null recorders and tolerance
 * of all invocations.
 */
class ObservabilityContractTest {

    @Test
    void observabilityNoOpReturnsNonNullMetricsRecorder() {
        // Why: Observability.NO_OP must return a non-null MetricsRecorder so call sites
        // can always invoke recorder methods without null-checks.
        Observability obs = Observability.NO_OP;

        assertNotNull(obs.metrics(), "NO_OP metrics must not be null");
    }

    @Test
    void observabilityNoOpReturnsNonNullTracingBridge() {
        // Why: Observability.NO_OP must return a non-null TracingBridge so call sites
        // can always invoke bridge methods without null-checks.
        Observability obs = Observability.NO_OP;

        assertNotNull(obs.tracing(), "NO_OP tracing must not be null");
    }

    @Test
    void metricsRecorderNoOpAcceptsIncrementCounterCall() {
        // Why: NO_OP recorder must accept every call without throwing, enabling
        // dispatch code to call it unconditionally.
        MetricsRecorder recorder = MetricsRecorder.NO_OP;

        assertDoesNotThrow(
                           () -> recorder.incrementCounter("test.counter", Map.of("tag", "value")),
                           "NO_OP must accept incrementCounter");
    }

    @Test
    void metricsRecorderNoOpAcceptsRecordTimerCall() {
        // Why: NO_OP recorder must accept timer calls without throwing.
        MetricsRecorder recorder = MetricsRecorder.NO_OP;

        assertDoesNotThrow(
                           () -> recorder.recordTimer("test.timer", Duration.ofMillis(100), Map.of("tag", "value")),
                           "NO_OP must accept recordTimer");
    }

    @Test
    void metricsRecorderNoOpAcceptsRecordGaugeCall() {
        // Why: NO_OP recorder must accept gauge calls without throwing.
        MetricsRecorder recorder = MetricsRecorder.NO_OP;

        assertDoesNotThrow(
                           () -> recorder.recordGauge("test.gauge", 42L, Map.of("tag", "value")),
                           "NO_OP must accept recordGauge");
    }

    @Test
    void tracingBridgeNoOpAcceptsStartSpanCall() {
        // Why: NO_OP bridge must accept span creation without throwing.
        TracingBridge bridge = TracingBridge.NO_OP;

        assertDoesNotThrow(
                           () -> bridge.startSpan("test.span", Map.of("attr", "value")),
                           "NO_OP must accept startSpan");
    }

    @Test
    void tracingBridgeNoOpSpanAcceptsSetAttribute() {
        // Why: NO_OP span must accept attribute setting without throwing.
        TracingBridge.Span span = TracingBridge.NO_OP.startSpan("test.span", Map.of());

        assertDoesNotThrow(
                           () -> span.setAttribute("key", "value"), "NO_OP span must accept setAttribute");
    }

    @Test
    void tracingBridgeNoOpSpanAcceptsRecordException() {
        // Why: NO_OP span must accept exception recording without throwing.
        TracingBridge.Span span = TracingBridge.NO_OP.startSpan("test.span", Map.of());

        assertDoesNotThrow(
                           () -> span.recordException(new RuntimeException("test")),
                           "NO_OP span must accept recordException");
    }

    @Test
    void tracingBridgeNoOpSpanAcceptsClose() {
        // Why: NO_OP span must accept close without throwing.
        TracingBridge.Span span = TracingBridge.NO_OP.startSpan("test.span", Map.of());

        assertDoesNotThrow(span::close, "NO_OP span must accept close");
    }
}
