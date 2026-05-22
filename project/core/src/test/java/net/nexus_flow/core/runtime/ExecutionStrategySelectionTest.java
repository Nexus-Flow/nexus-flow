package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Exhaustive {@code ExecutionMode → ExecutionStrategy} mapping, including the inline execution
 * semantics of the {@link ExecutionStrategy.AsynchronousDurable} variant.
 *
 * <p>The {@code switch} inside {@link ExecutionStrategy#fromMode} has no {@code default} branch, so
 * adding a new permit on either {@link ExecutionMode} or {@link ExecutionStrategy} must surface
 * here at compile time. These tests pin the runtime mapping and the inline execution semantics of
 * the durable strategy (durability lives in the per-runtime outbox sink, not in the strategy's
 * {@code run(...)} body — see {@link ExecutionStrategy.AsynchronousDurable} Javadoc).
 */
class ExecutionStrategySelectionTest {

    @Test
    void synchronous_mode_picksInline() {
        try (ExecutorService es = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutionStrategy strategy = ExecutionStrategy.fromMode(ExecutionMode.synchronous(), es);
            assertInstanceOf(ExecutionStrategy.Inline.class, strategy);
        }
    }

    @Test
    void asynchronousInMemory_mode_picksVirtualThread_andWiresProvidedExecutor() {
        try (ExecutorService es = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutionStrategy               strategy =
                    ExecutionStrategy.fromMode(ExecutionMode.asynchronousInMemory(), es);
            ExecutionStrategy.VirtualThread vt       =
                    assertInstanceOf(ExecutionStrategy.VirtualThread.class, strategy);
            assertSame(es, vt.executor(), "VirtualThread strategy must use the executor we passed in");
        }
    }

    @Test
    void asynchronousDurable_mode_resolvesToDurableStrategy_since_4() {
        // closeout — the AsynchronousDurable arm of
        // fromMode(...) returns a concrete (non-throwing) strategy
        // whose run(...) semantics are Inline. Durability is provided
        // by the per-runtime outbox sink during the post-handler
        // event drain (see HandlerEventDrain), not by this strategy.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ExecutionStrategy strategy =
                    ExecutionStrategy.fromMode(ExecutionMode.asynchronousDurable(), executor);
            assertNotNull(strategy);
            assertInstanceOf(
                             ExecutionStrategy.AsynchronousDurable.class,
                             strategy,
                             "fromMode(AsynchronousDurable) must yield ExecutionStrategy.AsynchronousDurable");
        }
    }

    @Test
    void rejectsNullArguments() {
        try (ExecutorService es = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThrows(NullPointerException.class, () -> ExecutionStrategy.fromMode(null, es));
            assertThrows(
                         NullPointerException.class,
                         () -> ExecutionStrategy.fromMode(ExecutionMode.synchronous(), null));
        }
    }

    // ----------------------------------------------------------------
    // closeout — durable strategy execution semantics.
    //
    // The strategy used to throw UnsupportedOperationException on
    // every run(...) call so callers could not bypass the outbox
    // boundary. After the closeout the durable strategy is a real
    // executor (Inline-equivalent) because the outbox boundary lives
    // in the per-runtime event drain, not in the strategy. The
    // resolver enforces the outbox-bound precondition (see
    // ExecutionStrategyResolverTest); the strategy itself is free to
    // run the task.
    // ----------------------------------------------------------------

    @Test
    void asynchronousDurable_strategy_runsCallable_inline_returningValue() throws Exception {
        ExecutionStrategy.AsynchronousDurable strategy = new ExecutionStrategy.AsynchronousDurable();
        ExecutionContext                      ctx      = ExecutionContext.root();

        Thread                  caller = Thread.currentThread();
        AtomicReference<Thread> ran    = new AtomicReference<>();

        String value =
                strategy.run(
                             () -> {
                                 ran.set(Thread.currentThread());
                                 return "ok";
                             },
                             ctx);

        assertSame(
                   caller,
                   ran.get(),
                   "AsynchronousDurable must run the Callable on the caller thread (Inline semantics).");
        assertTrue("ok".equals(value), "Callable return value must be propagated verbatim.");
    }

    @Test
    void asynchronousDurable_strategy_runsRunnable_inline() {
        ExecutionStrategy.AsynchronousDurable strategy = new ExecutionStrategy.AsynchronousDurable();
        ExecutionContext                      ctx      = ExecutionContext.root();

        Thread                  caller   = Thread.currentThread();
        AtomicReference<Thread> ran      = new AtomicReference<>();
        AtomicBoolean           executed = new AtomicBoolean(false);

        strategy.run(
                     () -> {
                         ran.set(Thread.currentThread());
                         executed.set(true);
                     },
                     ctx);

        assertTrue(
                   executed.get(), "AsynchronousDurable.run(Runnable) must execute the task synchronously.");
        assertSame(
                   caller,
                   ran.get(),
                   "AsynchronousDurable must run the Runnable on the caller thread (Inline semantics).");
    }

    @Test
    void asynchronousDurable_strategy_propagatesExceptionVerbatim() {
        ExecutionStrategy.AsynchronousDurable strategy = new ExecutionStrategy.AsynchronousDurable();
        ExecutionContext                      ctx      = ExecutionContext.root();
        IllegalStateException                 boom     = new IllegalStateException("kaboom");

        IllegalStateException thrown =
                assertThrows(
                             IllegalStateException.class,
                             () -> strategy.run(
                                                () -> {
                                                    throw boom;
                                                },
                                                ctx));
        assertSame(boom, thrown, "Durable strategy must propagate exceptions verbatim.");
    }
}
