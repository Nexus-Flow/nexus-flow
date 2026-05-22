package net.nexus_flow.benchmarks;

import net.nexus_flow.core.types.TypeReference;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Exercises {@link TypeReference#hashCode()} and {@link TypeReference#equals(Object)}.
 * The hash is cached at construction time, so {@code hashCode()} is a constant-time
 * field read; {@code equals(Object)} short-circuits on reference identity of the
 * underlying {@code Class<?>}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class TypeReferenceHashBenchmark {

    private TypeReference<String> ref;

    @Setup
    public void setup() {
        ref = new TypeReference<>(String.class);
    }

    @Benchmark
    public void hashCode_cached(Blackhole bh) {
        bh.consume(ref.hashCode());
    }

    @Benchmark
    public void equals_fastPath_identical(Blackhole bh) {
        bh.consume(ref.equals(ref));
    }

    @Benchmark
    public void equals_fastPath_differentInstanceSameType(Blackhole bh) {
        // Same Class<?> identity inside two distinct TypeReferences;
        // exercises the fast-path `this.type == other.type`.
        TypeReference<String> other = new TypeReference<>(String.class);
        bh.consume(ref.equals(other));
    }
}

