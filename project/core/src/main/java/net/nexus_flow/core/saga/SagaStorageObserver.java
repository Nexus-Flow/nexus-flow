package net.nexus_flow.core.saga;

/**
 * Push-notification SPI for {@link SagaStorage}. Callers register an observer when they
 * need to react immediately to state changes — for example, the
 * {@link SagaCompletionAwaiter} wakes on terminal transitions instead of polling.
 *
 * <h2>Why optional and push-only</h2>
 *
 * Polling is a correct fallback for every backend, but it costs at minimum
 * {@code 1 / pollInterval} reads per active waiter. When the backend natively supports
 * push (JDBC LISTEN/NOTIFY, Redis pub-sub, in-memory direct notification), the observer
 * lets the waiter consume zero polls. The framework's {@link SagaStorage#subscribe}
 * default is a no-op subscription so backends without push degrade gracefully.
 *
 * <h2>Threading</h2>
 *
 * Implementations invoke the observer on whatever thread completed the save — for the
 * in-memory backend that is the saver's thread (synchronous under the per-saga lock); for
 * a JDBC backend it would be the LISTEN-pump thread. Observer implementations MUST be
 * fast (best is "enqueue an event and return") — slow observers stall the storage.
 *
 * <h2>Failure isolation</h2>
 *
 * The framework wraps every observer call in a catch-all; an observer that throws is
 * logged and the save continues. Observers MUST NOT use exceptions for control flow.
 */
@FunctionalInterface
public interface SagaStorageObserver {

    /**
     * Notification: the saga {@code (sagaType, correlationKey)} just transitioned through
     * the storage to {@code newState}.
     *
     * @param sagaType       the saga's type discriminator
     * @param correlationKey the saga instance correlation key
     * @param newState       the just-persisted state
     */
    void onSagaStateChanged(String sagaType, String correlationKey, SagaState newState);

    /**
     * Handle returned by {@link SagaStorage#subscribe}. Closing the subscription releases
     * the storage-side resources and ensures the observer receives no further events.
     */
    @FunctionalInterface
    interface Subscription extends AutoCloseable {
        /** Idempotent close — subsequent invocations are no-ops. */
        @Override
        void close();

        /** No-op subscription returned by backends that do not support push notifications. */
        Subscription NO_OP = () -> {
        };
    }
}
