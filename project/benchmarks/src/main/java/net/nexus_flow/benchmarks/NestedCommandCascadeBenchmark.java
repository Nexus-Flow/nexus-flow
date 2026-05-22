package net.nexus_flow.benchmarks;

import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
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
 * Nested command dispatch cascade A→B→C: handler A dispatches command B which dispatches command
 * C. Measures the per-level dispatch overhead — every level allocates a fresh
 * {@code InvocationContext}, opens its own {@code FlowScope}, and runs through the interceptor
 * chain.
 *
 * <p>The {@link #depth} parameter sweeps 1 (no cascade — single dispatch), 2 (A→B), 3 (A→B→C) so
 * the per-level marginal cost is derivable by subtraction. The {@link #policyShape} param checks
 * whether policy choice affects cascade cost (it should be policy-neutral under success since
 * each level resolves independently).
 *
 * <p>The success path is the dominant production scenario; a failing cascade is covered by
 * {@code ErrorPropagationCascadeTest} for correctness — here we focus on the steady-state cost
 * that the framework must keep low so deeply-nested aggregate workflows do not pay quadratic
 * overhead.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class NestedCommandCascadeBenchmark {

    public record CmdA() {
    }

    public record CmdB() {
    }

    public record CmdC() {
    }

    @Param({"1", "2", "3"})
    public int depth;

    @Param({"FAIL_FAST", "COLLECT"})
    public String policyShape;

    private FlowRuntime      runtime;
    private Command<CmdA>    cmdA;
    private Command<CmdB>    cmdB;
    private Command<CmdC>    cmdC;
    private ExecutionContext ctx;
    private ErrorPolicy      policy;

    @Setup
    public void setup() {
        runtime = FlowRuntime.builder().build();
        runtime.commands().register(new AbstractReturnCommandHandler<CmdC, Integer>() {
            @Override
            protected Integer handle(CmdC cmd) {
                return 3;
            }
        });
        runtime.commands().register(new AbstractReturnCommandHandler<CmdB, Integer>() {
            @Override
            protected Integer handle(CmdB cmd) {
                DispatchResult<Integer> r = runtime.commands().dispatchAndReturnResult(
                                                                                       Command.<CmdC>builder().body(new CmdC()).build(),
                                                                                       ctx, policy);
                return r instanceof DispatchResult.Success<Integer> s ? s.value() : 2;
            }
        });
        runtime.commands().register(new AbstractReturnCommandHandler<CmdA, Integer>() {
            @Override
            protected Integer handle(CmdA cmd) {
                DispatchResult<Integer> r = runtime.commands().dispatchAndReturnResult(
                                                                                       Command.<CmdB>builder().body(new CmdB()).build(),
                                                                                       ctx, policy);
                return r instanceof DispatchResult.Success<Integer> s ? s.value() : 1;
            }
        });
        cmdA = Command.<CmdA>builder().body(new CmdA()).build();
        cmdB = Command.<CmdB>builder().body(new CmdB()).build();
        cmdC = Command.<CmdC>builder().body(new CmdC()).build();
        ctx  = ExecutionContext.root();
        policy = "FAIL_FAST".equals(policyShape) ? ErrorPolicy.failFast() : ErrorPolicy.collectFailures();
    }

    @TearDown
    public void tearDown() {
        runtime.close();
    }

    @Benchmark
    public void cascadeDispatch(Blackhole bh) {
        DispatchResult<Integer> r = switch (depth) {
            case 1  -> runtime.commands().dispatchAndReturnResult(cmdC, ctx, policy);
            case 2  -> runtime.commands().dispatchAndReturnResult(cmdB, ctx, policy);
            default -> runtime.commands().dispatchAndReturnResult(cmdA, ctx, policy);
        };
        bh.consume(r);
    }
}
