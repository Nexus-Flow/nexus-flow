package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.DomainEvent;
import org.junit.jupiter.api.Test;

/**
 * Audit §6 T9 (closes audit-B3). {@link ScopedDomainEventContext} uses a static {@code
 * ThreadLocal<Sink> FALLBACK} for code paths that record events without first binding the scoped
 * sink via {@code ScopedValue.where(SINK, …)}. The fallback is the per-carrier-thread leak vector
 * that the audit flagged: any worker thread (outbox, scheduled, saga) that calls {@code
 * Aggregate.recordEvent(…)} without first binding the scope accumulates events into the carrier
 * thread's slot until either:
 *
 * <ol>
 * <li>{@link DomainEventContext#clearEvents()} is called on that same thread, or
 * <li>the thread terminates.
 * </ol>
 *
 * <p>The cleanup mechanism ({@code FALLBACK.remove()} inside {@code clearEvents()}) exists and is
 * correct — but no test enforced the contract. This regression pins it: events recorded on a fresh
 * thread without scope binding land in {@code FALLBACK}; calling {@code clearEvents()} on that
 * thread releases the sink so a subsequent {@code recordEvent} on the same thread starts empty.
 *
 * <p><strong>Why a separate test thread.</strong> Each test runs on the JUnit worker thread, which
 * already has a populated FALLBACK from prior tests. Running this test on its own thread isolates
 * the assertion from contamination.
 */
class ScopedDomainEventContextFallbackLeaksOnWorkerThreadsTest {

    static final class Beat extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Beat() {
            super(UUID.randomUUID().toString());
        }
    }

    @Test
    void fallback_isCleared_whenClearEventsIsCalled_onSameThread() throws Exception {
        DomainEventContext         ctx     = DomainEventContext.current();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread t =
                new Thread(
                        () -> {
                            try {
                                // No FlowScope binding here — we want to exercise the FALLBACK path.
                                assertEquals(
                                             0,
                                             ctx.getEvents().size(),
                                             "fresh thread must start with an empty FALLBACK sink");

                                ctx.recordEvent(new Beat());
                                ctx.recordEvent(new Beat());
                                assertEquals(
                                             2, ctx.getEvents().size(), "two recordEvent calls must accumulate in FALLBACK");

                                ctx.clearEvents();
                                assertEquals(
                                             0,
                                             ctx.getEvents().size(),
                                             "clearEvents() MUST clear the FALLBACK sink — without this the worker "
                                                     + "thread leaks events across unrelated dispatches");

                                // Sanity: another record on the same thread starts from 0.
                                ctx.recordEvent(new Beat());
                                assertEquals(1, ctx.getEvents().size());
                            } catch (Throwable th) {
                                failure.set(th);
                            }
                        },
                        "fallback-leak-test-thread");
        t.start();
        t.join(5_000L);
        Throwable err = failure.get();
        if (err != null) {
            throw new AssertionError("worker-thread assertion failed", err);
        }
        assertTrue(!t.isAlive(), "worker thread did not terminate within 5s");
    }

    @Test
    void fallback_isolatedPerThread_noCrossThreadLeak() throws Exception {
        DomainEventContext         ctx     = DomainEventContext.current();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread tA =
                new Thread(
                        () -> {
                            try {
                                ctx.recordEvent(new Beat());
                                ctx.recordEvent(new Beat());
                                ctx.recordEvent(new Beat());
                                assertEquals(3, ctx.getEvents().size(), "thread A must observe 3 in FALLBACK");
                            } catch (Throwable th) {
                                failure.set(th);
                            } finally {
                                ctx.clearEvents();
                            }
                        },
                        "fallback-leak-thread-A");

        Thread tB =
                new Thread(
                        () -> {
                            try {
                                // Different thread — FALLBACK is a ThreadLocal, so this must start empty.
                                assertEquals(
                                             0,
                                             ctx.getEvents().size(),
                                             "thread B FALLBACK must NOT see thread A's events — ThreadLocal isolation");
                                ctx.recordEvent(new Beat());
                                assertEquals(1, ctx.getEvents().size());
                            } catch (Throwable th) {
                                failure.compareAndSet(null, th);
                            } finally {
                                ctx.clearEvents();
                            }
                        },
                        "fallback-leak-thread-B");

        tA.start();
        tA.join(5_000L);
        tB.start();
        tB.join(5_000L);
        Throwable err = failure.get();
        if (err != null) {
            throw new AssertionError("cross-thread leak detected", err);
        }
    }

    @Test
    void recordEvent_returnsTheSameSink_onConsecutiveCallsWithinTheSameThread() throws Exception {
        DomainEventContext         ctx     = DomainEventContext.current();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread t =
                new Thread(
                        () -> {
                            try {
                                ctx.recordEvent(new Beat());
                                DomainEvent firstReadFirst = ctx.getEvents().getFirst();
                                ctx.recordEvent(new Beat());
                                DomainEvent secondReadFirst = ctx.getEvents().getFirst();
                                // The first event must be the same instance across both reads — the sink is the
                                // SAME object, growing in place, not a fresh snapshot per read.
                                assertEquals(
                                             firstReadFirst,
                                             secondReadFirst,
                                             "consecutive getEvents() reads on the same thread must observe the same "
                                                     + "underlying sink (FALLBACK is a stable ThreadLocal reference)");
                            } catch (Throwable th) {
                                failure.set(th);
                            } finally {
                                ctx.clearEvents();
                            }
                        },
                        "fallback-stability-test");
        t.start();
        t.join(5_000L);
        Throwable err = failure.get();
        if (err != null) {
            throw new AssertionError(err);
        }
    }
}
