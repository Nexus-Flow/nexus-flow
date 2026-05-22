package net.nexus_flow.benchmarks;

import net.nexus_flow.core.runtime.registry.DispatchPlan;
import net.nexus_flow.core.runtime.registry.HandlerRegistry;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Compares the cost of looking up a dispatch plan via
 * {@link HandlerRegistry#planFor(Class)} (which is backed by a
 * {@link ClassValue}-cached snapshot) against a plain
 * {@link ConcurrentHashMap#get(Object)} keyed by {@code Class}.
 *
 * <p>Both data sets pre-register {@code N} handler classes; the lookup picks
 * one round-robin to keep branch prediction honest.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class DispatchPlanLookupBenchmark {

    @Param({"1", "10", "100"})
    public int typeCount;

    private HandlerRegistry<Object, Object> registry;
    private ConcurrentHashMap<Class<?>, DispatchPlan<Object, Object>> baseline;
    private List<Class<?>> keys;

    /** Distinct holder classes used as registration keys. */
    static final class T0 {} static final class T1 {} static final class T2 {} static final class T3 {}
    static final class T4 {} static final class T5 {} static final class T6 {} static final class T7 {}
    static final class T8 {} static final class T9 {}

    @Setup
    public void setup() {
        registry = new HandlerRegistry<>();
        baseline = new ConcurrentHashMap<>();
        keys = new ArrayList<>(typeCount);
        Class<?>[] all = new Class<?>[]{
                T0.class, T1.class, T2.class, T3.class, T4.class,
                T5.class, T6.class, T7.class, T8.class, T9.class
        };
        for (int i = 0; i < typeCount; i++) {
            // After 10 classes we wrap around — the same class will be
            // registered multiple times (registry de-dups by overwriting
            // the snapshot; baseline overwrites the map value). The
            // *lookup* set still rotates through the configured count.
            Class<?> k = all[i % all.length];
            keys.add(k);
            registry.registerInvoker(k, (msg, ctx) -> null, /*order=*/0);
            baseline.put(k, DispatchPlan.empty());
        }
    }

    private int idx;

    @Benchmark
    public void classValueCachedLookup(Blackhole bh) {
        Class<?> k = keys.get(idx++ % keys.size());
        bh.consume(registry.planFor(k));
    }

    @Benchmark
    public void concurrentHashMapLookup(Blackhole bh) {
        Class<?> k = keys.get(idx++ % keys.size());
        bh.consume(baseline.get(k));
    }
}

