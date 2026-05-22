package net.nexus_flow.benchmarks;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Deadline-check overhead on the dispatch hot path. Compares dispatches with no deadline
 * (baseline; the runtime skips the deadline arithmetic entirely) vs dispatches with a far-future
 * deadline (the runtime computes {@code Instant.now().compareTo(deadline)} on every poll point).
 *
 * <p>The deadline check is documented as cheap — pure {@code Instant} comparison — but it fires
 * at every cancellation poll inside the dispatch path. This bench pins the absolute cost so any
 * regression that adds per-call {@code Instant.now()} allocation surfaces.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class DeadlineCheckCascadeBenchmark {

    public record Ping() {
    }

    @Param({"NONE", "FAR_FUTURE"})
    public String deadlineMode;

    private FlowRuntime      runtime;
    private Command<Ping>    cmd;
    private ExecutionContext ctxNoDeadline;
    private ExecutionContext ctxWithDeadline;

    @Setup
    public void setup() {
        runtime = FlowRuntime.builder().build();
        runtime.commands().register(new AbstractReturnCommandHandler<Ping, Integer>() {
            @Override
            protected Integer handle(Ping cmd) {
                return 1;
            }
        });
        cmd             = Command.<Ping>builder().body(new Ping()).build();
        ctxNoDeadline   = ExecutionContext.root();
        ctxWithDeadline = ExecutionContext.rootWithTimeout(Duration.ofHours(1));
    }

    @TearDown
    public void tearDown() {
        runtime.close();
    }

    @Benchmark
    public void dispatch(Blackhole bh) {
        ExecutionContext ctx = "NONE".equals(deadlineMode) ? ctxNoDeadline : ctxWithDeadline;
        bh.consume(runtime.commands().dispatchAndReturnResult(cmd, ctx, ErrorPolicy.failFast()));
    }
}
