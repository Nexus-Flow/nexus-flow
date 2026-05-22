package net.nexus_flow.core.outbox;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import org.jspecify.annotations.Nullable;

/**
 * SPI invoked exactly once when an outbox row transitions to
 * {@link OutboxStatus#FAILED_TERMINAL}. The framework's previous shape required operators
 * to write a polling job against the outbox to surface terminal failures — easy to forget,
 * easy to drift between deployments. The handler is the explicit hook that runs INSIDE the
 * worker's failure path so every terminal failure is observed at the moment it happens.
 *
 * <h2>What implementations typically do</h2>
 *
 * <ul>
 * <li>Publish the failed row to a dead-letter Kafka topic / RabbitMQ queue for downstream
 * triage tooling.
 * <li>Open a ticket in the operator's incident system with the row id, payload type, and
 * a sanitised error summary.
 * <li>Page on-call if the failure matches a critical-event predicate.
 * <li>Record a discrete counter in {@link net.nexus_flow.core.observability.MetricsRecorder}
 * so dashboards reflect the terminal-failure rate in addition to attempt rate.
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * Invoked synchronously on the {@link OutboxWorker}'s daemon thread immediately after the
 * storage transition completes. Implementations MUST be fast (best to push the row to a
 * queue/log and return) — slow handlers block the worker's progress on the next row.
 *
 * <h2>Failure isolation</h2>
 *
 * The worker wraps every {@code onTerminalFailure(...)} call in a catch-all. A handler that
 * throws is logged at WARNING; the worker continues with the next row. Implementations
 * MUST NOT use exceptions as control flow — return cleanly and signal observability via
 * the metrics SPI.
 *
 * <h2>Default implementation</h2>
 *
 * {@link #LOG_ONLY} logs the terminal failure at WARNING. It is the framework default so
 * deployments without an explicit handler still emit a visible signal.
 */
@FunctionalInterface
public interface DeadLetterHandler {

    /**
     * Called once when {@code record} transitions to {@link OutboxStatus#FAILED_TERMINAL}.
     *
     * @param record the now-terminal row (status = FAILED_TERMINAL); the {@code lastError}
     *               field carries the flattened stack trace
     * @param cause  the underlying exception that triggered the terminal transition, or
     *               {@code null} when the transition was operator-driven
     */
    void onTerminalFailure(OutboxRecord record, @Nullable Throwable cause);

    /** Logs every terminal failure at WARNING; the framework default. */
    DeadLetterHandler LOG_ONLY = new DeadLetterHandler() {
        private final Logger log = System.getLogger(DeadLetterHandler.class.getName());

        @Override
        public void onTerminalFailure(OutboxRecord record, @Nullable Throwable cause) {
            log.log(Level.WARNING,
                    () -> "outbox row "
                            + record.outboxId()
                            + " (payloadType="
                            + record.payloadType().getName()
                            + ", attempts="
                            + record.attempts()
                            + ") transitioned to FAILED_TERMINAL — manual triage required"
                            + (cause == null ? "" : ": " + cause.getMessage()));
        }
    };

    /**
     * No-op handler. Use only in tests where the terminal failure path is exercised
     * directly via storage and the worker-level callback would add noise.
     */
    DeadLetterHandler NO_OP = (record, cause) -> {
    };
}
