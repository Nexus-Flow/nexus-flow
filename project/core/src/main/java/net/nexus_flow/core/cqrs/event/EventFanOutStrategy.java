package net.nexus_flow.core.cqrs.event;

/**
 * Sealed strategy that decides HOW {@link DefaultEventBus} fans an event out to its
 * registered listeners.
 *
 * <p>The hierarchy is intentionally explicit so the {@link DefaultEventBus}-internal
 * pattern-match over variants is exhaustive — adding a new variant forces every consumer
 * to compile-fail until the new case is handled. This is the {@code core}-side anchor for
 * future adapter-module dispatch shapes (Disruptor-pipelined, SPSC-per-listener, Kafka
 * fan-out) without re-opening the bus's dispatch decision tree.
 *
 * <h2>Current variants</h2>
 *
 * <ul>
 * <li>{@link Sequential} — listeners run on the caller thread in registration order.
 * Ordering invariant for outbox persisters, in-order audit trails, anything that
 * needs deterministic listener observation. <strong>Default.</strong>
 * <li>{@link StructuredParallel} — listeners run in parallel via
 * {@link java.util.concurrent.StructuredTaskScope}. Opt-in only when every listener for
 * the event class declares {@code parallelSafe() == true}. Higher throughput for
 * CPU-bound listeners; pays ~20 µs per fan-out for the {@code VirtualThread} fork
 * overhead, so amortises only when each listener's work exceeds that threshold.
 * </ul>
 *
 * <h2>Future-reserved seats</h2>
 *
 * Adapter-module extension points whose seats are reserved in the sealed contract but
 * whose concrete implementations live outside {@code core}:
 *
 * <ul>
 * <li>{@code DisruptorPipelined} — LMAX-Disruptor ring buffer with pre-allocated
 * consumer threads polling for events. Wins when listener latency is I/O-bound and
 * there are many listeners.
 * <li>{@code SpscPipelined} — per-listener {@link
 * net.nexus_flow.core.runtime.concurrent.SpscRingBuffer} so the producer (the bus)
 * hands off lock-free to a dedicated consumer thread per listener.
 * <li>{@code KafkaProducer} — fan-out to a Kafka topic with one consumer group per
 * listener. Useful when listeners must survive process restart.
 * </ul>
 *
 * <p>Adding such variants requires extending the {@code permits} clause AND handling the
 * new case in {@link DefaultEventBus}'s strategy switch; the compiler enforces the
 * second step.
 *
 * @see net.nexus_flow.core.outbox.EventDeliveryStrategy for the analogous strategy that
 *      decides INTER-runtime delivery (inline event-bus vs durable outbox vs hybrid). The
 *      two strategies compose: {@code EventDeliveryStrategy} picks WHO publishes the
 *      event (the bus or the outbox worker); {@code EventFanOutStrategy} picks HOW the
 *      bus internally distributes it to listeners once it owns the publish.
 */
public sealed interface EventFanOutStrategy
        permits EventFanOutStrategy.Sequential, EventFanOutStrategy.StructuredParallel {

    /** Singleton {@link Sequential} instance — stateless, safe to share process-wide. */
    Sequential SEQUENTIAL = new Sequential();

    /** Singleton {@link StructuredParallel} instance. */
    StructuredParallel STRUCTURED_PARALLEL = new StructuredParallel();

    /**
     * Sequential, in-registration-order, on-caller-thread fan-out. The dispatch invariant
     * for ordering-sensitive listeners.
     */
    final class Sequential implements EventFanOutStrategy {
        Sequential() {
        }
    }

    /**
     * Parallel fan-out via {@link java.util.concurrent.StructuredTaskScope}. Listeners
     * run on forked {@code VirtualThread} subtasks; the caller blocks until every subtask
     * completes (or the surrounding {@code ErrorPolicy} short-circuits). {@link
     * DefaultEventBus} downgrades to {@link #SEQUENTIAL} at dispatch time when the
     * dispatch plan's {@code allParallelSafe()} is {@code false} OR the listener
     * count is {@code <= 1} — the per-fork overhead does not amortise below that
     * threshold.
     */
    final class StructuredParallel implements EventFanOutStrategy {
        StructuredParallel() {
        }
    }

    /** @return the safe-default {@link #SEQUENTIAL} strategy. */
    static EventFanOutStrategy sequential() {
        return SEQUENTIAL;
    }

    /** @return the opt-in {@link #STRUCTURED_PARALLEL} strategy. */
    static EventFanOutStrategy structuredParallel() {
        return STRUCTURED_PARALLEL;
    }
}
