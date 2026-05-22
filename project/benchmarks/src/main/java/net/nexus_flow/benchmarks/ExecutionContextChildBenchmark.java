package net.nexus_flow.benchmarks;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.ids.MessageId;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * {@link ExecutionContext} hot-path benchmark. Pins the per-dispatch construction cost of:
 *
 * <ul>
 * <li>{@code root()} — 4 fresh ids + immutable empty attributes.
 * <li>{@code rootWithTimeout} — root + deadline computation.
 * <li>{@code childContextFor} — every nested dispatch derives a fresh child carrying the
 * parent's deadline + cancellation + attributes; the {@link MessageId}-to-{@code CausationId}
 * cached promotion is exercised here.
 * <li>{@code withAttribute} — copy-on-write attribute mutation; the most-common interceptor
 * touch.
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class ExecutionContextChildBenchmark {

    private ExecutionContext parent;
    private ExecutionContext parentWithDeadline;
    private MessageId        childMessageId;

    @Setup
    public void setup() {
        parent             = ExecutionContext.root();
        parentWithDeadline = ExecutionContext.rootWithTimeout(Duration.ofMinutes(1));
        childMessageId     = MessageId.random();
    }

    @Benchmark
    public void rootContext(Blackhole bh) {
        bh.consume(ExecutionContext.root());
    }

    @Benchmark
    public void rootWithTimeout(Blackhole bh) {
        bh.consume(ExecutionContext.rootWithTimeout(Duration.ofMinutes(1)));
    }

    @Benchmark
    public void childContext(Blackhole bh) {
        bh.consume(parent.childContextFor(childMessageId));
    }

    @Benchmark
    public void childContextWithDeadline(Blackhole bh) {
        bh.consume(parentWithDeadline.childContextFor(childMessageId));
    }

    @Benchmark
    public void withAttribute(Blackhole bh) {
        bh.consume(parent.withAttribute("traceFlag", Boolean.TRUE));
    }
}
