package net.nexus_flow.benchmarks;

import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.cqrs.query.AbstractQueryHandler;
import net.nexus_flow.core.cqrs.query.Query;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Query-bus end-to-end dispatch through {@code DefaultQueryBus.ask}: registration-time
 * MethodHandle binding, hot-path plan lookup, ScopedValue context bind, handler invocation.
 *
 * <p>Three variants:
 *
 * <ul>
 * <li>{@code askDispatch} — steady-state {@code ask()} call returning a sentinel value.
 * <li>{@code askWithExplicitTimeout} — overload that activates the {@code CompletableFuture}
 * timeout path (slower; pinning the asymmetry helps prevent accidental adoption on the hot
 * path).
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class QueryDispatchE2EBenchmark {

    public record GetOrderById(String id) {
    }

    private FlowRuntime          runtime;
    private Query<GetOrderById>  query;

    @Setup
    public void setup() {
        runtime = FlowRuntime.builder().build();
        runtime.queries().register(new AbstractQueryHandler<GetOrderById, String>() {
            @Override
            public String handle(GetOrderById body) {
                return "order:" + body.id();
            }
        });
        query = Query.<GetOrderById>builder().body(new GetOrderById("42")).build();
    }

    @TearDown
    public void tearDown() {
        runtime.close();
    }

    @Benchmark
    public void askDispatch(Blackhole bh) {
        bh.consume(runtime.queries().ask(query));
    }
}
