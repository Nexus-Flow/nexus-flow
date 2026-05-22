package net.nexus_flow.benchmarks;

import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.dispatch.DispatchChain;
import net.nexus_flow.core.runtime.dispatch.DispatchInterceptor;
import net.nexus_flow.core.runtime.dispatch.InvocationContext;
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
 * Cost of an interceptor chain at depths {@code 0, 1, 4, 8, 16} layers. Pins the per-interceptor
 * marginal cost — the framework's promise is "zero interceptors → byte-identical fast path" so
 * depth=0 should be indistinguishable from {@link CommandDispatchE2EBenchmark#successPath}.
 *
 * <p>Each interceptor is a no-op pass-through. Production interceptors (logging, MDC, tracing)
 * carry their own cost on top of this baseline; here we measure pure chain plumbing.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class InterceptorChainDepthBenchmark {

    public record Cmd(int seq) {
    }

    /** No-op interceptor: pure chain traversal cost. */
    public static final class PassThroughInterceptor implements DispatchInterceptor {
        @Override
        public <R> DispatchResult<R> intercept(InvocationContext ctx, DispatchChain<R> chain) {
            return chain.proceed();
        }
    }

    @Param({"0", "1", "4", "8", "16"})
    public int chainDepth;

    private FlowRuntime      runtime;
    private Command<Cmd>     command;
    private ExecutionContext ctx;

    @Setup
    public void setup() {
        FlowRuntime.Builder b = FlowRuntime.builder();
        for (int i = 0; i < chainDepth; i++) {
            b.interceptor(new PassThroughInterceptor());
        }
        runtime = b.build();
        runtime.commands().register(new AbstractReturnCommandHandler<Cmd, Integer>() {
            @Override
            protected Integer handle(Cmd cmd) {
                return cmd.seq();
            }
        });
        command = Command.<Cmd>builder().body(new Cmd(42)).build();
        ctx     = ExecutionContext.root();
    }

    @TearDown
    public void tearDown() {
        runtime.close();
    }

    @Benchmark
    public void dispatchThroughChain(Blackhole bh) {
        bh.consume(runtime.commands().dispatchAndReturnResult(command, ctx, ErrorPolicy.failFast()));
    }
}
