package net.nexus_flow.benchmarks;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.runtime.CancellationToken;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Focused micro-benchmark isolating the JIT-inlined {@code Objects.requireNonNull} cost
 * inside record compact constructors vs an equivalent class with no validation.
 *
 * <p>Settles whether the 6× {@code requireNonNull} in {@link ExecutionContext}'s compact
 * constructor adds measurable cost vs a class with a private unchecked constructor. At
 * 1 M req/sec hot dispatch volume, a 0.4–1 ns saving translates to 400 ms–1 s per CPU
 * core per second — the level of saving that does matter at hyperscale.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class ExecutionContextValidationCostBenchmark {

    /** Class equivalent of ExecutionContext with NO validation in the constructor. */
    public static final class UncheckedClass {
        public final MessageId         messageId;
        public final TraceId           traceId;
        public final CorrelationId     correlationId;
        public final CausationId       causationId;
        public final java.time.Instant deadline;
        public final CancellationToken cancellation;
        public final Map<String, Object> attributes;

        private UncheckedClass(
                MessageId messageId,
                TraceId traceId,
                CorrelationId correlationId,
                CausationId causationId,
                java.time.Instant deadline,
                CancellationToken cancellation,
                Map<String, Object> attributes) {
            this.messageId    = messageId;
            this.traceId      = traceId;
            this.correlationId = correlationId;
            this.causationId  = causationId;
            this.deadline     = deadline;
            this.cancellation = cancellation;
            this.attributes   = attributes;
        }

        public static UncheckedClass unchecked(
                MessageId messageId,
                TraceId traceId,
                CorrelationId correlationId,
                CausationId causationId,
                java.time.Instant deadline,
                CancellationToken cancellation,
                Map<String, Object> attributes) {
            return new UncheckedClass(messageId, traceId, correlationId, causationId,
                                       deadline, cancellation, attributes);
        }
    }

    private MessageId         messageId;
    private TraceId           traceId;
    private CorrelationId     correlationId;
    private CausationId       causationId;
    private CancellationToken cancellation;
    private Map<String, Object> emptyAttrs;

    @Setup
    public void setup() {
        messageId    = MessageId.random();
        traceId      = TraceId.random();
        correlationId = CorrelationId.random();
        causationId  = CausationId.ROOT;
        cancellation = CancellationToken.create();
        emptyAttrs   = Map.of();
    }

    @Benchmark
    public ExecutionContext recordValidated() {
        return new ExecutionContext(
                messageId, traceId, correlationId, causationId,
                null, null, null, cancellation, emptyAttrs);
    }

    @Benchmark
    public UncheckedClass classUnchecked() {
        return UncheckedClass.unchecked(
                messageId, traceId, correlationId, causationId,
                null, cancellation, emptyAttrs);
    }

}
