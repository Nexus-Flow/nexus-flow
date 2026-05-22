package net.nexus_flow.benchmarks;

import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Pins two hot-path optimisations:
 *
 * <ol>
 * <li>{@code DispatchResult.SUCCESS_VOID} singleton vs per-call {@code new Success<>(null)}.
 * <li>{@code ScopedValue} read vs {@code ThreadLocal.get} on the dispatcher's per-dispatch
 *     {@code InvocationContext.current()} lookup.
 * </ol>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class HotPathOptimizationBenchmark {

    private static final ThreadLocal<Object>  TL = ThreadLocal.withInitial(Object::new);
    private static final ScopedValue<Object>  SV = ScopedValue.newInstance();
    private static final Object               SV_VALUE = new Object();

    /**
     * Old: per-call allocation of {@code new Success<>(null)}. Used to be the shape of
     * {@code DispatchResult.success(null)} before the singleton was introduced.
     */
    @Benchmark
    public void dispatchResultNewSuccessAllocation(Blackhole bh) {
        bh.consume(new DispatchResult.Success<Void>(null));
    }

    /** New: returns the pre-built {@code SUCCESS_VOID} singleton. */
    @Benchmark
    public void dispatchResultSuccessSingleton(Blackhole bh) {
        bh.consume(DispatchResult.success(null));
    }

    /**
     * Baseline: {@code ThreadLocal.get()} — what {@code InvocationContext.current()} used
     * to do under the previous {@code ThreadLocal} shape.
     */
    @Benchmark
    public void threadLocalRead(Blackhole bh) {
        bh.consume(TL.get());
    }

    /**
     * Optimised: {@code ScopedValue.get()} inside a bound scope — what
     * {@code InvocationContext.current()} now does. Stack-rooted lookup along the
     * structured-scope chain.
     */
    @Benchmark
    public void scopedValueRead(Blackhole bh) throws Exception {
        ScopedValue.where(SV, SV_VALUE).call(() -> {
            bh.consume(SV.get());
            return null;
        });
    }

    // ---------- Cancellation flyweight ----------

    /** Existing shape: {@code new FlowCancellationException()} + {@code new Failure<>(ex)}. */
    @Benchmark
    public void cancelExceptionAllocation(Blackhole bh) {
        bh.consume(new net.nexus_flow.core.runtime.result.FlowCancellationException());
    }

    /**
     * Existing shape: full {@code DispatchResult.failure(new FlowCancellationException())}.
     * The dominant cost is {@link Throwable#fillInStackTrace()} which captures the stack.
     */
    @Benchmark
    public void cancelFailureAllocation(Blackhole bh) {
        bh.consume(net.nexus_flow.core.runtime.result.DispatchResult.failure(
                new net.nexus_flow.core.runtime.result.FlowCancellationException()));
    }

    @Benchmark
    public void cancelStacklessSingleton(Blackhole bh) {
        bh.consume(net.nexus_flow.core.runtime.result.FlowCancellationException.CANCELLED);
    }

    private static final net.nexus_flow.core.runtime.result.DispatchResult<Void> CACHED_CANCEL_FAILURE =
            net.nexus_flow.core.runtime.result.DispatchResult.failure(
                    net.nexus_flow.core.runtime.result.FlowCancellationException.CANCELLED);

    @Benchmark
    public void cancelCachedFailure(Blackhole bh) {
        bh.consume(CACHED_CANCEL_FAILURE);
    }
}
