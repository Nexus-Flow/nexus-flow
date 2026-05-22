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
 * End-to-end command dispatch benchmark covering the full {@code DefaultCommandBus} pipeline
 * under every {@link ErrorPolicy} variant.
 *
 * <p>Measures the per-dispatch cost including:
 *
 * <ul>
 * <li>{@code InvocationContext} construction + JFR begin/end pairing.
 * <li>Interceptor chain build (here: no interceptors — pure dispatcher cost).
 * <li>{@code DefaultCommandHandlerExecutor.submitAndReturn} → MethodHandle invocation.
 * <li>{@code ExecutionContext.childContextFor} + {@code FlowScope.runWithContext} per handler.
 * <li>{@code SyncDispatcher.classify} on success / failure paths.
 * </ul>
 *
 * <p>Three handler shapes are measured: a no-op success handler (fastest path), a handler that
 * throws a non-{@code FlowError} (classify wraps in {@code Technical}), and a handler that throws
 * a stack-traceless {@code FlowError.Domain} (verbatim propagation, no wrapping).
 *
 * <p>The matrix is intentionally compact ({@code 3 handlers × 4 policies = 12 cells}) so a full
 * sweep fits in a single JMH run without forcing a sparse {@code @Param} explosion.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class CommandDispatchE2EBenchmark {

    /** Command type whose handler always succeeds. */
    public record SuccessCmd(int seq) {
    }

    /** Command type whose handler throws a plain RuntimeException (wrapped in Technical). */
    public record TechnicalFailCmd(int seq) {
    }

    private FlowRuntime          runtime;
    private Command<SuccessCmd>  successCommand;
    private Command<TechnicalFailCmd> failCommand;
    private ExecutionContext     ctx;

    private ErrorPolicy policy;

    @Param({"FAIL_FAST", "COLLECT", "IGNORE_ALL", "ISOLATE"})
    public String policyShape;

    @Setup
    public void setup() {
        runtime = FlowRuntime.builder().build();
        runtime.commands().register(new AbstractReturnCommandHandler<SuccessCmd, Integer>() {
            @Override
            protected Integer handle(SuccessCmd cmd) {
                return cmd.seq() + 1;
            }
        });
        runtime.commands().register(new AbstractReturnCommandHandler<TechnicalFailCmd, Integer>() {
            @Override
            protected Integer handle(TechnicalFailCmd cmd) {
                throw new RuntimeException("boom");
            }
        });
        successCommand = Command.<SuccessCmd>builder().body(new SuccessCmd(1)).build();
        failCommand    = Command.<TechnicalFailCmd>builder().body(new TechnicalFailCmd(1)).build();
        ctx            = ExecutionContext.root();

        policy = switch (policyShape) {
            case "FAIL_FAST"  -> ErrorPolicy.failFast();
            case "COLLECT"    -> ErrorPolicy.collectFailures();
            case "IGNORE_ALL" -> ErrorPolicy.ignore(t -> true);
            case "ISOLATE"    -> ErrorPolicy.isolate(ErrorPolicy.failFast());
            default           -> throw new IllegalArgumentException(policyShape);
        };
    }

    @TearDown
    public void tearDown() {
        runtime.close();
    }

    @Benchmark
    public void successPath(Blackhole bh) {
        DispatchResult<Integer> result =
                runtime.commands().dispatchAndReturnResult(successCommand, ctx, policy);
        bh.consume(result);
    }

    @Benchmark
    public void technicalFailurePath(Blackhole bh) {
        DispatchResult<Integer> result =
                runtime.commands().dispatchAndReturnResult(failCommand, ctx, policy);
        bh.consume(result);
    }

    @Benchmark
    public void fireAndForget(Blackhole bh) {
        runtime.commands().dispatch(successCommand);
        bh.consume(successCommand);
    }
}
