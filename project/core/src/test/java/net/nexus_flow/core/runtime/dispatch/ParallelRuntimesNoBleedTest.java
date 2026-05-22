package net.nexus_flow.core.runtime.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.*;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.junit.jupiter.api.Test;

/**
 * Two {@link FlowRuntime} instances dispatching in parallel must observe <strong>no bleed</strong>
 * through their respective {@link DispatchInterceptor} chains. Extends the fan-out invariant pinned
 * by {@code TimingInterceptorAttributePropagationTest} to the multi-runtime case.
 *
 * <p>Each runtime is wired with a custom interceptor that records the dispatch duration under a
 * runtime-specific key into a runtime-specific map. After concurrent dispatch, the runtimeA-keyed
 * entry must appear only in the runtimeA map (and equally for B). A leak in either direction would
 * mean the bus / interceptor wiring is still sharing mutable state behind the per-runtime API.
 */
class ParallelRuntimesNoBleedTest {

    record Slow(int sleepMs) {
    }

    /**
     * Interceptor that writes {@code dispatch.durationMs} under a <em>runtime-scoped</em> key and
     * into a runtime-scoped map. Each runtime gets its own instance with a distinct key/map pair so a
     * cross-talk regression lights up as a wrong key landing in the wrong map.
     */
    private static final class ScopedTimingInterceptor implements DispatchInterceptor {
        private final String            key;
        private final Map<String, Long> sink;

        ScopedTimingInterceptor(String key, Map<String, Long> sink) {
            this.key  = key;
            this.sink = sink;
        }

        @Override
        public <R> DispatchResult<R> intercept(InvocationContext ctx, DispatchChain<R> chain) {
            long start = System.nanoTime();
            try {
                return chain.proceed();
            } finally {
                long durationMs = (System.nanoTime() - start) / 1_000_000L;
                ctx.attributes().put(key, durationMs);
                sink.put(key, durationMs);
            }
        }
    }

    @Test
    void twoRuntimes_dispatchingInParallel_doNotBleedInterceptorStateAcrossEachOther() throws InterruptedException, ExecutionException, TimeoutException {

        Map<String, Long> sinkA = new ConcurrentHashMap<>();
        Map<String, Long> sinkB = new ConcurrentHashMap<>();

        try (FlowRuntime a =
                FlowRuntime.builder()
                        .interceptor(new ScopedTimingInterceptor("runtime.A.durationMs", sinkA))
                        .build();
                FlowRuntime b =
                        FlowRuntime.builder()
                                .interceptor(new ScopedTimingInterceptor("runtime.B.durationMs", sinkB))
                                .build()) {

            var handler =
                    new AbstractReturnCommandHandler<Slow, String>() {
                        @Override
                        protected String handle(Slow command) {
                            try {
                                Thread.sleep(command.sleepMs());
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                            return "done";
                        }
                    };

            // Each runtime gets its OWN handler instance — registering
            // the same logical handler in both buses pins the registry
            // isolation. The brief makes this an explicit requirement.
            a.commands().register(handler);
            b.commands().register(handler);

            CyclicBarrier   startGate = new CyclicBarrier(2);
            ExecutorService racer     = Executors.newFixedThreadPool(2);
            try {
                // Different sleep budgets so a leak (B's wall-clock
                // landing in A's bag) would be detectable in principle.
                Future<DispatchResult<String>> rA =
                        racer.submit(
                                     () -> {
                                         startGate.await();
                                         return a.commands()
                                                 .dispatchAndReturnResult(
                                                                          Command.<Slow>builder().body(new Slow(40)).build(),
                                                                          ExecutionContext.root(),
                                                                          ErrorPolicy.failFast());
                                     });
                Future<DispatchResult<String>> rB =
                        racer.submit(
                                     () -> {
                                         startGate.await();
                                         return b.commands()
                                                 .dispatchAndReturnResult(
                                                                          Command.<Slow>builder().body(new Slow(120)).build(),
                                                                          ExecutionContext.root(),
                                                                          ErrorPolicy.failFast());
                                     });

                assertInstanceOf(DispatchResult.Success.class, rA.get(5, TimeUnit.SECONDS));
                assertInstanceOf(DispatchResult.Success.class, rB.get(5, TimeUnit.SECONDS));
            } finally {
                racer.shutdown();
                if (!racer.awaitTermination(2, TimeUnit.SECONDS)) {
                    racer.shutdownNow();
                }
            }

            // The runtimeA-scoped key must NOT have landed in
            // runtimeB's sink, and vice versa. This is the multi-runtime
            // extension of TimingInterceptorAttributePropagationTest's
            // sibling fan-out invariant.
            assertNotNull(
                          sinkA.get("runtime.A.durationMs"),
                          "runtime A's interceptor must have recorded A's duration");
            assertNotNull(
                          sinkB.get("runtime.B.durationMs"),
                          "runtime B's interceptor must have recorded B's duration");
            assertNull(
                       sinkA.get("runtime.B.durationMs"),
                       " runtime A must NOT observe runtime B's interceptor key");
            assertNull(
                       sinkB.get("runtime.A.durationMs"),
                       " runtime B must NOT observe runtime A's interceptor key");
            assertEquals(1, sinkA.size(), "runtime A's sink must contain exactly its own dispatch entry");
            assertEquals(1, sinkB.size(), "runtime B's sink must contain exactly its own dispatch entry");
        }
    }
}
