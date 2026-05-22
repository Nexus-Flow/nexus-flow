package net.nexus_flow.benchmarks;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.runtime.concurrent.SpscRingBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Single-threaded offer+poll round-trip micro-benchmark. Pins the offer/poll
 * cost of three queue shapes when the producer-consumer cardinality is statically 1:
 *
 * <ul>
 * <li>{@link SpscRingBuffer} — wait-free SPSC with padded cursors + VarHandle ops.
 * <li>{@link ArrayBlockingQueue} — JDK-standard MPMC bounded queue (intrinsic monitor).
 * <li>{@link LinkedBlockingQueue} — JDK-standard MPMC unbounded queue (intrinsic
 * monitor + per-node allocation per offer).
 * </ul>
 *
 * <p>The benchmark is single-threaded (the producer and consumer run on the same JMH
 * thread). Multi-threaded contention numbers belong in a separate {@link
 * org.openjdk.jmh.annotations.Threads}-parameterised bench.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class SpscQueueBenchmark {

    private SpscRingBuffer<Integer>     spsc;
    private ArrayBlockingQueue<Integer> abq;
    private LinkedBlockingQueue<Integer> lbq;
    private Integer                      element;

    @Setup
    public void setup() {
        spsc    = new SpscRingBuffer<>(1024);
        abq     = new ArrayBlockingQueue<>(1024);
        lbq     = new LinkedBlockingQueue<>();
        element = 42;
    }

    @Benchmark
    public void spscOfferPoll(Blackhole bh) {
        spsc.offer(element);
        bh.consume(spsc.poll());
    }

    @Benchmark
    public void arrayBlockingQueueOfferPoll(Blackhole bh) {
        abq.offer(element);
        bh.consume(abq.poll());
    }

    @Benchmark
    public void linkedBlockingQueueOfferPoll(Blackhole bh) {
        lbq.offer(element);
        bh.consume(lbq.poll());
    }
}
