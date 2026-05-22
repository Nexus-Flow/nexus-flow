package net.nexus_flow.benchmarks;

import java.io.Serial;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
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
 * Event fan-out under the cartesian product of {@code (listenerCount × policy × fanOutMode ×
 * mixedFailureMode)} — the matrix the runtime is configured against in production.
 *
 * <p>The fan-out mode toggle exercises sequential dispatch ({@code parallelListeners=false}) and
 * opt-in parallel dispatch ({@code parallelListeners=true} + every listener declares
 * {@code parallelSafe()=true}). Sequential is the dispatch invariant for ordering-sensitive
 * listeners (e.g. outbox-bound persisters); parallel is the perf escape hatch for read-only
 * listeners.
 *
 * <p>Mixed-failure mode injects one failing listener at index {@code listenerCount/2} so the
 * policy logic actually fires — {@code FailFast} short-circuits, {@code CollectFailures} runs all
 * remaining listeners and aggregates, {@code IgnoreFailures} swallows on predicate match,
 * {@code IsolatePerBoundary} contains failures into a {@code PartialFailure}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class EventFanOutMatrixBenchmark {

    public static final class FanOutEvt extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        public FanOutEvt(String aggId) {
            super(aggId);
        }
    }

    /** Noop listener; cheap, allocation-free invocation body. */
    public static final class NoopListener extends AbstractDomainEventListener<FanOutEvt> {
        public int count;
        private final boolean parallelSafe;

        public NoopListener(boolean parallelSafe) {
            this.parallelSafe = parallelSafe;
        }

        @Override
        public boolean parallelSafe() {
            return parallelSafe;
        }

        @Override
        public void handle(FanOutEvt event) {
            count++;
        }
    }

    /** Failing listener used in mixed-failure cells. */
    public static final class FailingListener extends AbstractDomainEventListener<FanOutEvt> {
        private final boolean parallelSafe;

        public FailingListener(boolean parallelSafe) {
            this.parallelSafe = parallelSafe;
        }

        @Override
        public boolean parallelSafe() {
            return parallelSafe;
        }

        @Override
        public void handle(FanOutEvt event) {
            throw new RuntimeException("listener-boom");
        }
    }

    @Param({"1", "4", "16"})
    public int listenerCount;

    @Param({"SEQUENTIAL", "PARALLEL"})
    public String fanOutMode;

    @Param({"FAIL_FAST", "COLLECT", "IGNORE_ALL", "ISOLATE"})
    public String policyShape;

    @Param({"ALL_SUCCESS", "ONE_FAILURE"})
    public String failureMode;

    private FlowRuntime      runtime;
    private FanOutEvt        event;
    private ExecutionContext ctx;
    private ErrorPolicy      policy;

    @Setup
    public void setup() {
        boolean parallel = "PARALLEL".equals(fanOutMode);
        runtime = FlowRuntime.builder().parallelListeners(parallel).build();
        int failIdx = "ONE_FAILURE".equals(failureMode) ? listenerCount / 2 : -1;
        for (int i = 0; i < listenerCount; i++) {
            if (i == failIdx) {
                runtime.events().register(new FailingListener(parallel));
            } else {
                runtime.events().register(new NoopListener(parallel));
            }
        }
        event = new FanOutEvt("agg-fanout");
        ctx   = ExecutionContext.root();
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
    public void dispatchResult(Blackhole bh) {
        bh.consume(runtime.events().dispatchResult(event, ctx, policy));
    }
}
