package net.nexus_flow.core.runtime.dispatch;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.ThrowableUtils;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import org.jspecify.annotations.Nullable;

/**
 * Per-handler concurrency control via a single {@link Semaphore} keyed by the handler's identity.
 *
 * <p><b>Why a Semaphore and not a per-handler executor.</b> The original design wired one {@link
 * java.util.concurrent.ExecutorService} per handler so that each handler had its own bounded queue.
 * An earlier audit ("Concurrency control via Semaphore is broken") flagged this as both wasteful
 * and incorrect: a single shared virtual-thread executor is enough, and bounded admission is
 * exactly what {@link Semaphore} expresses. The runtime now exposes a single shared executor (see
 * {@link FlowRuntime#executor()}) and uses this gate to throttle concurrent invocations of the same
 * handler.
 *
 * <p><b>Acquire-before, release-after.</b> The audit also flagged a bug where the legacy code
 * released the permit before invoking the handler, making the gate a no-op. {@link
 * #runGated(Object, int, Supplier)} acquires the permit first and releases it in {@code finally}
 * <em>after</em> the supplier returns or throws.
 *
 * <p><b>concurrencyLevel = 0 ⇒ no gating.</b> A handler that explicitly declares {@code 0} permits
 * (the legacy default) opts out of throttling; the gate becomes a pass-through. {@code
 * concurrencyLevel = 1} serialises concurrent invocations; {@code N} permits up to {@code N}
 * concurrent invocations.
 *
 * <p>Thread-safe by design: the backing map uses {@link ConcurrentHashMap} and gate creation is
 * idempotent through {@code computeIfAbsent}.
 */
public final class HandlerConcurrencyGate {

    /**
     * Snapshot of a single gate, exposed for diagnostics. The wait counter is best-effort (it is a
     * hot path) but never under-reports.
     *
     * @param permits      the total number of permits this gate was created with
     * @param available    the number of permits currently available (i.e., not held)
     * @param waitingCount the approximate number of threads currently blocked waiting to acquire a
     *                     permit
     */
    public record GateStats(int permits, int available, long waitingCount) {
    }

    private static final class Gate {
        final int        permits;
        final Semaphore  semaphore;
        final AtomicLong waiting = new AtomicLong();

        Gate(int permits) {
            this.permits   = permits;
            this.semaphore = new Semaphore(permits);
        }
    }

    private final ConcurrentHashMap<Object, Gate> gates = new ConcurrentHashMap<>();

    /**
     * Run {@code body} under the per-key gate. {@code permits} must equal the configured concurrency
     * level for that key; passing a different value throws {@link IllegalArgumentException} so
     * misconfigurations surface at the first conflict.
     *
     * <p>{@code permits == 0} skips gating entirely.
     *
     * <p>If the calling thread is interrupted while waiting for a permit the interrupt flag is
     * restored and a {@link FlowCancellationException} is thrown — that is the runtime's
     * cooperative-cancellation contract.
     */
    public <R> R runGated(Object key, int permits, Supplier<? extends R> body) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(body, "body");
        if (permits < 0) {
            throw new IllegalArgumentException("permits must be >= 0: " + permits);
        }
        if (permits == 0) {
            return body.get();
        }
        Gate gate = gates.computeIfAbsent(key, _ -> new Gate(permits));
        if (gate.permits != permits) {
            throw new IllegalArgumentException(
                    "Inconsistent concurrency level for key="
                            + key
                            + ": existing="
                            + gate.permits
                            + ", requested="
                            + permits);
        }
        gate.waiting.incrementAndGet();
        try {
            gate.semaphore.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ThrowableUtils.withSuppressed(
                                                new FlowCancellationException("Interrupted while waiting for handler permit"), ie);
        } finally {
            gate.waiting.decrementAndGet();
        }
        try {
            // V2: release-after-execution — the permit is held for the entire
            // body so concurrencyLevel actually bounds in-flight handlers.
            return body.get();
        } finally {
            gate.semaphore.release();
        }
    }

    /**
     * Variant that allows the body to surface {@link InterruptedException} cleanly. Mostly useful
     * inside {@link java.util.concurrent.StructuredTaskScope} forks.
     */
    public <R> R runGatedInterruptible(Object key, int permits, InterruptibleSupplier<R> body) throws InterruptedException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(body, "body");
        if (permits < 0) {
            throw new IllegalArgumentException("permits must be >= 0: " + permits);
        }
        if (permits == 0) {
            return body.get();
        }
        Gate gate = gates.computeIfAbsent(key, _ -> new Gate(permits));
        if (gate.permits != permits) {
            throw new IllegalArgumentException(
                    "Inconsistent concurrency level for key="
                            + key
                            + ": existing="
                            + gate.permits
                            + ", requested="
                            + permits);
        }
        gate.waiting.incrementAndGet();
        try {
            gate.semaphore.acquire();
        } finally {
            gate.waiting.decrementAndGet();
        }
        try {
            return body.get();
        } finally {
            gate.semaphore.release();
        }
    }

    /** Diagnostics: returns gate stats, or {@code null} if {@code key} is unknown. */
    public @Nullable GateStats stats(Object key) {
        Gate gate = gates.get(key);
        if (gate == null) {
            return null;
        }
        return new GateStats(gate.permits, gate.semaphore.availablePermits(), gate.waiting.get());
    }

    /** Test helper: snapshot the entire gate table. */
    public Map<Object, GateStats> snapshot() {
        Map<Object, GateStats> out = HashMap.newHashMap(gates.size());
        gates.forEach(
                      (k, g) -> out.put(k, new GateStats(g.permits, g.semaphore.availablePermits(), g.waiting.get())));
        return Map.copyOf(out);
    }

    /** Body of a gated section that may throw {@link InterruptedException}. */
    @FunctionalInterface
    public interface InterruptibleSupplier<R> {
        /**
         * Executes the gated body.
         *
         * @return the result produced by the body
         * @throws InterruptedException if the body or an underlying blocking call is interrupted; the
         *                              caller (i.e., the gate) is responsible for restoring interrupt state if it catches this
         */
        @SuppressWarnings("RedundantThrows")
        R get() throws InterruptedException;
    }

    /**
     * Test helper: best-effort timed wait for {@code n} threads to be queued at {@code key}.
     *
     * @param key           the handler identity key; must not be {@code null}
     * @param n             minimum number of waiting threads before this method returns {@code true}
     * @param timeoutMillis maximum time to poll in milliseconds
     * @return {@code true} if at least {@code n} threads are waiting within the timeout; {@code
     *     false} if the timeout elapsed first
     * @throws InterruptedException if the calling thread is interrupted while sleeping between polls
     */
    boolean awaitWaiters(Object key, int n, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            Gate gate = gates.get(key);
            if (gate != null && gate.waiting.get() >= n) {
                return true;
            }
            //noinspection BusyWait
            Thread.sleep(5);
        }
        return false;
    }
}
