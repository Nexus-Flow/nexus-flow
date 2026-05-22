package net.nexus_flow.benchmarks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Pins the 6 remaining "potential" fronts with JMH so the decision to apply (or skip) each is
 * data-driven, not opinion-driven.
 *
 * <ol>
 * <li>{@link CopyOnWriteArrayList} vs {@code AtomicReference<List<T>>} snapshot iteration —
 *     observer/listener read pattern.
 * <li>{@code Optional.ofNullable(null)} singleton hit vs allocation — hot read.
 * <li>Per-call object allocation for a small record vs a thread-local builder pool — modern
 *     GC behaviour.
 * <li>{@link MessageId#asCausation()} per-call allocation vs cached delegate.
 * <li>{@link TreeMap#ceilingEntry} vs sorted {@code long[]} binary search on the hash ring's
 *     hot lookup.
 * <li>Manual cache-line padding (long-array stuffer) vs raw {@code AtomicLong} for false-
 *     sharing-prone counters.
 * </ol>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class RemainingFrontiersBenchmark {

    // ---------- shared state ----------

    private final CopyOnWriteArrayList<String>  cowObservers = new CopyOnWriteArrayList<>();
    private final AtomicReference<List<String>> atomicRefObservers = new AtomicReference<>(List.of());

    private final TreeMap<Long, String> treeMap = new TreeMap<>();
    private long[]                      sortedKeys;
    private String[]                    sortedValues;

    private MessageId messageId;

    @Setup
    public void setup() {
        for (int i = 0; i < 10; i++) {
            cowObservers.add("listener-" + i);
            atomicRefObservers.set(append(atomicRefObservers.get(), "listener-" + i));
            treeMap.put((long) (i * 1_000_000_007L), "peer-" + i);
        }
        // Materialise sorted arrays from the treeMap so the binary-search benchmark uses the
        // same key set.
        sortedKeys   = treeMap.keySet().stream().mapToLong(Long::longValue).toArray();
        sortedValues = treeMap.values().toArray(new String[0]);
        messageId    = new MessageId(UUID.randomUUID());
    }

    private static List<String> append(List<String> src, String item) {
        List<String> copy = new ArrayList<>(src.size() + 1);
        copy.addAll(src);
        copy.add(item);
        return List.copyOf(copy);
    }

    // ---------- 1. observers iteration ----------

    @Benchmark
    public void cowObserversIterate(Blackhole bh) {
        for (String s : cowObservers) {
            bh.consume(s);
        }
    }

    @Benchmark
    public void atomicRefObserversIterate(Blackhole bh) {
        for (String s : atomicRefObservers.get()) {
            bh.consume(s);
        }
    }

    // ---------- 2. Optional.ofNullable(null) vs Optional.empty() ----------

    private static final Optional<String> CACHED_EMPTY = Optional.empty();

    @Benchmark
    public void optionalOfNullableNull(Blackhole bh) {
        bh.consume(Optional.ofNullable(null));
    }

    @Benchmark
    public void optionalEmptySingleton(Blackhole bh) {
        bh.consume(CACHED_EMPTY);
    }

    // ---------- 3. record allocation vs builder pool ----------
    // Modern GC handles short-lived allocations very well. This benchmark measures whether
    // pooling actually wins. A "record" allocation per call is the baseline; the pooled
    // approach reuses a thread-local mutable holder. Pooling has a cost (the lookup +
    // possibly mutability hazards).

    private record SmallRecord(long a, long b, long c) {
    }

    private static final ThreadLocal<long[]> POOLED_HOLDER =
            ThreadLocal.withInitial(() -> new long[3]);

    @Benchmark
    public void recordAllocation(Blackhole bh) {
        bh.consume(new SmallRecord(1L, 2L, 3L));
    }

    @Benchmark
    public void pooledHolder(Blackhole bh) {
        long[] holder = POOLED_HOLDER.get();
        holder[0] = 1L;
        holder[1] = 2L;
        holder[2] = 3L;
        bh.consume(holder);
    }

    // ---------- 4. MessageId.asCausation() ----------

    @Benchmark
    public void messageIdAsCausationAllocation(Blackhole bh) {
        bh.consume(messageId.asCausation());
    }

    @Benchmark
    public void messageIdAsCausationCached(Blackhole bh) {
        // Best-case for caching: returning a static cached instance. Pins the upper bound of
        // any memoisation strategy.
        bh.consume(CACHED_CAUSATION);
    }

    private static final CausationId CACHED_CAUSATION = new CausationId(UUID.randomUUID());

    // ---------- 5. TreeMap.ceilingEntry vs long[] binary search ----------

    @Benchmark
    public void treeMapCeilingLookup(Blackhole bh) {
        var entry = treeMap.ceilingEntry(500_000_000L);
        bh.consume(entry == null ? "" : entry.getValue());
    }

    @Benchmark
    public void sortedArrayBinarySearchLookup(Blackhole bh) {
        int idx = Arrays.binarySearch(sortedKeys, 500_000_000L);
        if (idx < 0) {
            idx = -idx - 1;
        }
        String found = idx < sortedKeys.length ? sortedValues[idx] : sortedValues[0];
        bh.consume(found);
    }

    // ---------- 6. false-sharing: padded vs raw AtomicLong under multi-thread contention ----
    // Manual cache-line padding (8 longs * 8 bytes = 64-byte cache line each side) prevents
    // false sharing when neighbouring fields are mutated by different cores. Requires no JVM
    // flag (unlike @Contended which needs -XX:-RestrictContended).

    private final java.util.concurrent.atomic.AtomicLong rawCounter1   = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong rawCounter2   = new java.util.concurrent.atomic.AtomicLong();
    @SuppressWarnings("unused")
    private long pad1, pad2, pad3, pad4, pad5, pad6, pad7;
    private final java.util.concurrent.atomic.AtomicLong paddedCounter1 = new java.util.concurrent.atomic.AtomicLong();
    @SuppressWarnings("unused")
    private long pad8, pad9, pad10, pad11, pad12, pad13, pad14;
    private final java.util.concurrent.atomic.AtomicLong paddedCounter2 = new java.util.concurrent.atomic.AtomicLong();
    @SuppressWarnings("unused")
    private long pad15, pad16, pad17, pad18, pad19, pad20, pad21;

    // PaddedAtomicLong wrapper from core — same field padding strategy but encapsulated.
    private final net.nexus_flow.core.runtime.concurrent.PaddedAtomicLong realPaddedCounter1 =
            new net.nexus_flow.core.runtime.concurrent.PaddedAtomicLong();
    private final net.nexus_flow.core.runtime.concurrent.PaddedAtomicLong realPaddedCounter2 =
            new net.nexus_flow.core.runtime.concurrent.PaddedAtomicLong();

    @Benchmark
    @org.openjdk.jmh.annotations.Group("fs8raw")
    @org.openjdk.jmh.annotations.GroupThreads(4)
    public void incrementRaw1(Blackhole bh) {
        bh.consume(rawCounter1.incrementAndGet());
    }

    @Benchmark
    @org.openjdk.jmh.annotations.Group("fs8raw")
    @org.openjdk.jmh.annotations.GroupThreads(4)
    public void incrementRaw2(Blackhole bh) {
        bh.consume(rawCounter2.incrementAndGet());
    }

    @Benchmark
    @org.openjdk.jmh.annotations.Group("fs8pad")
    @org.openjdk.jmh.annotations.GroupThreads(4)
    public void incrementPadded1(Blackhole bh) {
        bh.consume(paddedCounter1.incrementAndGet());
    }

    @Benchmark
    @org.openjdk.jmh.annotations.Group("fs8pad")
    @org.openjdk.jmh.annotations.GroupThreads(4)
    public void incrementPadded2(Blackhole bh) {
        bh.consume(paddedCounter2.incrementAndGet());
    }

    // Real implementation — PaddedAtomicLong from core.
    @Benchmark
    @org.openjdk.jmh.annotations.Group("fs8real")
    @org.openjdk.jmh.annotations.GroupThreads(4)
    public void incrementRealPadded1(Blackhole bh) {
        bh.consume(realPaddedCounter1.incrementAndGet());
    }

    @Benchmark
    @org.openjdk.jmh.annotations.Group("fs8real")
    @org.openjdk.jmh.annotations.GroupThreads(4)
    public void incrementRealPadded2(Blackhole bh) {
        bh.consume(realPaddedCounter2.incrementAndGet());
    }
}
