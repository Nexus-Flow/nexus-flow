package net.nexus_flow.benchmarks;

import java.io.Serial;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.dispatch.SyncDispatcher;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import net.nexus_flow.core.runtime.result.FlowDeadlineExceededException;
import net.nexus_flow.core.runtime.result.FlowError;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * {@link SyncDispatcher#classify} hot-path benchmark — the function fires on EVERY failed
 * dispatch and on every fan-out fold. Pins the per-call cost of each branch of the sealed
 * taxonomy switch.
 *
 * <p>Five paths:
 *
 * <ul>
 * <li>{@code FlowError.Technical} — passes through verbatim (best case).
 * <li>{@code FlowError.Domain} (user opt-in marker) — passes through verbatim.
 * <li>{@code FlowCancellationException.CANCELLED} (singleton) — verbatim.
 * <li>{@code FlowDeadlineExceededException} — verbatim.
 * <li>plain {@code RuntimeException} — wrapped in {@code FlowError.Technical} (worst case;
 * allocates a Technical record carrying the ExecutionContext).
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class ErrorClassificationBenchmark {

    public static final class DomainBoom extends RuntimeException implements FlowError.Domain {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    private ExecutionContext              ctx;
    private FlowError.Technical           technicalCarrier;
    private DomainBoom                    domain;
    private FlowDeadlineExceededException deadlineExpired;
    private RuntimeException              plain;

    @Setup
    public void setup() {
        ctx              = ExecutionContext.root();
        technicalCarrier = new FlowError.Technical(new RuntimeException("seed"), ctx);
        domain           = new DomainBoom();
        deadlineExpired  = new FlowDeadlineExceededException(java.time.Instant.now());
        plain            = new RuntimeException("plain");
    }

    @Benchmark
    public void classifyTechnical(Blackhole bh) {
        DispatchResult<Object> result = SyncDispatcher.classify(technicalCarrier, ctx, ErrorPolicy.failFast());
        bh.consume(result);
    }

    @Benchmark
    public void classifyDomain(Blackhole bh) {
        DispatchResult<Object> result = SyncDispatcher.classify(domain, ctx, ErrorPolicy.failFast());
        bh.consume(result);
    }

    @Benchmark
    public void classifyCancellationSingleton(Blackhole bh) {
        DispatchResult<Object> result =
                SyncDispatcher.classify(FlowCancellationException.CANCELLED, ctx, ErrorPolicy.failFast());
        bh.consume(result);
    }

    @Benchmark
    public void classifyDeadlineExceeded(Blackhole bh) {
        DispatchResult<Object> result =
                SyncDispatcher.classify(deadlineExpired, ctx, ErrorPolicy.failFast());
        bh.consume(result);
    }

    @Benchmark
    public void classifyPlainRuntimeWrapping(Blackhole bh) {
        DispatchResult<Object> result = SyncDispatcher.classify(plain, ctx, ErrorPolicy.failFast());
        bh.consume(result);
    }
}
