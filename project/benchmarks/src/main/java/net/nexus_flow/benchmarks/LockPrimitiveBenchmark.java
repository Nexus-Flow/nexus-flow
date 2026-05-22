package net.nexus_flow.benchmarks;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Pins the technical motivation for the lock primitive choices in
 * {@code InMemoryOutboxStorage.claimLock}, {@code InMemoryEventStore.globalLock} and
 * {@code InMemorySagaStorage.Slot.lock} — under sustained contention,
 * {@link ReentrantLock} (AQS-based parking) outperforms intrinsic {@code synchronized}
 * (heavyweight monitor inflation) on JDK 21+ post-biased-locking-removal.
 *
 * <p>Also pins {@link LongAdder} vs {@link AtomicLong} for the per-listener counter rewrite:
 * {@code LongAdder}'s striped-cell design avoids the cache-line ping-pong of CAS contention
 * on a single counter when the hot path is write-heavy.
 *
 * <p>Threads scale across the {@code @GroupThreads} count; the run mode is throughput so a
 * higher number is better.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class LockPrimitiveBenchmark {

    private final Object        intrinsic       = new Object();
    private final ReentrantLock reentrant       = new ReentrantLock();
    private final AtomicLong    atomicCounter   = new AtomicLong();
    private final LongAdder     adderCounter    = new LongAdder();
    private final Semaphore     fairSemaphore   = new Semaphore(4, true);
    private final Semaphore     unfairSemaphore = new Semaphore(4);

    private long counterUnderLock;

    @Benchmark
    @Group("mutex8")
    @GroupThreads(8)
    public void synchronizedMutex8(Blackhole bh) {
        synchronized (intrinsic) {
            counterUnderLock++;
            bh.consume(counterUnderLock);
        }
    }

    @Benchmark
    @Group("mutex8r")
    @GroupThreads(8)
    public void reentrantMutex8(Blackhole bh) {
        reentrant.lock();
        try {
            counterUnderLock++;
            bh.consume(counterUnderLock);
        } finally {
            reentrant.unlock();
        }
    }

    /**
     * Write-only contention on a single {@link AtomicLong}. Every thread hits the same
     * cache line; cross-CPU CAS retries throttle throughput. Reads (via {@link AtomicLong#get()})
     * are rare in the listener-stats use case and are NOT measured on the hot path.
     */
    @Benchmark
    @Group("counter8")
    @GroupThreads(8)
    public void atomicLongIncrement8(Blackhole bh) {
        atomicCounter.incrementAndGet();
        bh.consume(atomicCounter);
    }

    /**
     * Write-only contention on a {@link LongAdder}. Striped cells spread the CAS contention
     * across cache lines — under multi-thread write pressure the throughput should beat
     * {@code AtomicLong} by 2–10×, matching the {@code ListenerStats} rewrite motivation.
     */
    @Benchmark
    @Group("counter8a")
    @GroupThreads(8)
    public void longAdderIncrement8(Blackhole bh) {
        adderCounter.increment();
        bh.consume(adderCounter);
    }

    /**
     * Fair {@link Semaphore} (4 permits, 8 contending threads). Fair-mode enforces FIFO
     * acquire ordering through AQS; under sustained contention this serialises the acquire
     * transaction and devastates throughput.
     */
    @Benchmark
    @Group("sem8f")
    @GroupThreads(8)
    public void semaphoreFair8(Blackhole bh) throws InterruptedException {
        fairSemaphore.acquire();
        try {
            bh.consume(fairSemaphore);
        } finally {
            fairSemaphore.release();
        }
    }

    /**
     * Unfair {@link Semaphore} (4 permits, 8 contending threads). Allows barging — a thread
     * that just released can re-acquire ahead of waiters — at the cost of theoretical
     * starvation that does not materialise for short critical sections.
     */
    @Benchmark
    @Group("sem8u")
    @GroupThreads(8)
    public void semaphoreUnfair8(Blackhole bh) throws InterruptedException {
        unfairSemaphore.acquire();
        try {
            bh.consume(unfairSemaphore);
        } finally {
            unfairSemaphore.release();
        }
    }
}
