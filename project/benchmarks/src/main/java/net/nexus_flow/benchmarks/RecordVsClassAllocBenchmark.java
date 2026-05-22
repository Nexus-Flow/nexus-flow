package net.nexus_flow.benchmarks;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Hypothesis-driven micro-benchmark to settle the "records vs classes" perf question.
 *
 * <p>Three shapes for a 6-field immutable value carrier:
 *
 * <ul>
 * <li>{@code RecordValidated} — Java {@code record} with mandatory compact-constructor
 * validation (6× {@code requireNonNull}).
 * <li>{@code ClassValidated} — plain {@code final class} with explicit constructor that runs
 * the same 6× {@code requireNonNull} validation.
 * <li>{@code ClassUnchecked} — plain {@code final class} with private constructor + static
 * factory that SKIPS validation entirely. Models the "internal factory" pattern.
 * </ul>
 *
 * <p>The data settles whether records have an inherent cost vs equivalent classes, AND whether
 * skipping validation actually buys anything when the JIT inlines {@code requireNonNull}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class RecordVsClassAllocBenchmark {

    public record RecordValidated(String a, String b, String c, String d, String e, String f) {
        public RecordValidated {
            Objects.requireNonNull(a, "a");
            Objects.requireNonNull(b, "b");
            Objects.requireNonNull(c, "c");
            Objects.requireNonNull(d, "d");
            Objects.requireNonNull(e, "e");
            Objects.requireNonNull(f, "f");
        }
    }

    public static final class ClassValidated {
        private final String a;
        private final String b;
        private final String c;
        private final String d;
        private final String e;
        private final String f;

        public ClassValidated(String a, String b, String c, String d, String e, String f) {
            this.a = Objects.requireNonNull(a, "a");
            this.b = Objects.requireNonNull(b, "b");
            this.c = Objects.requireNonNull(c, "c");
            this.d = Objects.requireNonNull(d, "d");
            this.e = Objects.requireNonNull(e, "e");
            this.f = Objects.requireNonNull(f, "f");
        }
    }

    public static final class ClassUnchecked {
        private final String a;
        private final String b;
        private final String c;
        private final String d;
        private final String e;
        private final String f;

        private ClassUnchecked(String a, String b, String c, String d, String e, String f) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            this.e = e;
            this.f = f;
        }

        public static ClassUnchecked unchecked(
                String a, String b, String c, String d, String e, String f) {
            return new ClassUnchecked(a, b, c, d, e, f);
        }
    }

    private String a, b, c, d, e, f;

    @Setup
    public void setup() {
        a = "alpha";
        b = "beta";
        c = "gamma";
        d = "delta";
        e = "epsilon";
        f = "zeta";
    }

    @Benchmark
    public RecordValidated recordValidated() {
        return new RecordValidated(a, b, c, d, e, f);
    }

    @Benchmark
    public ClassValidated classValidated() {
        return new ClassValidated(a, b, c, d, e, f);
    }

    @Benchmark
    public ClassUnchecked classUnchecked() {
        return ClassUnchecked.unchecked(a, b, c, d, e, f);
    }
}
