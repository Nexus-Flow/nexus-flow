# Nexus Flow — Benchmarks

JMH benchmarks that capture baseline performance for hot paths on the dispatch pipeline (handler lookup, type-reference hashing, event fan-out). Use them to gate any change that touches a hot path: a regression in this report must be justified before being merged.

## Running

```bash
./gradlew :project:benchmarks:jmh
```

Defaults: 3 warmup iterations, 3 measurement iterations, 1 fork, 1 s per iteration. Override:

```bash
./gradlew :project:benchmarks:jmh \
    --args="-wi 5 -i 10 -f 2 -r 5s -prof gc \
            -rff build/reports/jmh/results.json -rf JSON"
```

Output: `project/benchmarks/build/reports/jmh/results.txt`.

## Benchmarks shipped

| Class | What it measures |
|---|---|
| `TypeReferenceHashBenchmark` | `TypeReference#hashCode` (cached at construction) and `equals` fast paths |
| `DispatchPlanLookupBenchmark` | `HandlerRegistry#planFor` (`ClassValue`-cached snapshot) vs `ConcurrentHashMap.get` at 1/10/100 keys |
| `EventFanOutBenchmark` | Full `DefaultEventBus#dispatchResult` + `dispatch` round-trip at 1/10/100 listeners |

## Baselines

> **Status (2026-05-19):** baseline numbers captured on Windows /
> Temurin-25.0.2+10-LTS using:
>
> `./gradlew :project:benchmarks:jmh`
>
> JMH defaults from this module: `-wi 3 -i 3 -f 1 -r 1s`.

### Captured baseline (2026-05-19)

| Benchmark | Params | Score | Unit |
|---|---|---:|---|
| `DispatchPlanLookupBenchmark.classValueCachedLookup` | `typeCount=1` | 3.644 | ns/op |
| `DispatchPlanLookupBenchmark.classValueCachedLookup` | `typeCount=10` | 3.641 | ns/op |
| `DispatchPlanLookupBenchmark.classValueCachedLookup` | `typeCount=100` | 3.640 | ns/op |
| `DispatchPlanLookupBenchmark.concurrentHashMapLookup` | `typeCount=1` | 2.861 | ns/op |
| `DispatchPlanLookupBenchmark.concurrentHashMapLookup` | `typeCount=10` | 3.081 | ns/op |
| `DispatchPlanLookupBenchmark.concurrentHashMapLookup` | `typeCount=100` | 3.082 | ns/op |
| `EventFanOutBenchmark.dispatchResult_failFast` | `listenerCount=1` | 1.250 | us/op |
| `EventFanOutBenchmark.dispatchResult_failFast` | `listenerCount=10` | 4.890 | us/op |
| `EventFanOutBenchmark.dispatchResult_failFast` | `listenerCount=100` | 41.847 | us/op |
| `EventFanOutBenchmark.dispatch_legacyFireAndForget` | `listenerCount=1` | 0.011 | us/op |
| `EventFanOutBenchmark.dispatch_legacyFireAndForget` | `listenerCount=10` | 0.058 | us/op |
| `EventFanOutBenchmark.dispatch_legacyFireAndForget` | `listenerCount=100` | 0.969 | us/op |
| `TypeReferenceHashBenchmark.equals_fastPath_differentInstanceSameType` | n/a | 2.405 | ns/op |
| `TypeReferenceHashBenchmark.equals_fastPath_identical` | n/a | 0.469 | ns/op |
| `TypeReferenceHashBenchmark.hashCode_cached` | n/a | 0.586 | ns/op |

### Methodology (binding)

- **Workstation.** Pin to a specific machine and record CPU, JDK, OS in the rows below. The initial captured baseline ran on macOS aarch64 / Temurin-25.0.2+10-LTS / Gradle 9.4.1.
- **Iterations.** Minimum `-wi 5 -i 10 -f 2 -r 5s` for any number that lands in this table.
- **Profilers.** Always include `-prof gc` to catch hidden allocations on the dispatch path.
- **Comparison rule.** A change is considered a regression if any cell drops by more than 10 % vs the baseline on the same machine.

### Raw report location

- `project/benchmarks/build/reports/jmh/results.txt`

### Expected qualitative outcome

- `ClassValue` lookup should beat `ConcurrentHashMap.get` by **1.3-2×** on a saturated dispatch loop.
- `TypeReference#hashCode` cached should beat the `Objects.hash(type)` baseline by **3-5×** (one field read vs. a varargs array allocation + `Arrays.hashCode`).
- `EventFanOutBenchmark` at N=1 should sit in the low-microsecond range; growth from N=1→N=100 should be **roughly linear** (sequential fan-out by design).

## Why no `me.champeau.jmh` plugin?

The third-party JMH Gradle plugin pulls extra config, generates an intermediate sourceset, and historically lags behind JDK previews. We pull JMH as a regular dependency and run `org.openjdk.jmh.Main` via a plain `JavaExec` task. No plugin lock-in; the benchmarks compile with the same `--enable-preview` toolchain as the rest of the build.

## Future benchmarks

- `CommandDispatchBenchmark` to measure the end-to-end command executor pipeline throughput.
- `BackpressureSaturationBenchmark` to measure the saturation policy under load.
- `OutboxDrainBenchmark` to measure `OutboxWorker` throughput.

