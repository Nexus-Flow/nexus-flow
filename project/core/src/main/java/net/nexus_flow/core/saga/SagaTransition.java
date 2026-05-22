package net.nexus_flow.core.saga;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.nexus_flow.core.ddd.DomainEvent;

/**
 * Sealed outcome of {@link Saga#handle(net.nexus_flow.core.eventsourcing.EventEnvelope,
 * SagaState)}.
 *
 * <p><strong>Saga transition variants:</strong> The saga indicates its next state via one of four
 * sealed types:
 *
 * <ul>
 * <li>{@link Continue} — saga remains {@link SagaStatus#RUNNING} with updated {@code newData}; no
 * compensation events emitted. Use this to accumulate state as events arrive.
 * <li>{@link Complete} — saga reaches the terminal {@link SagaStatus#COMPLETED} state. {@code
 *       finalData} is persisted as the last state for inspection / auditing. No further events are
 * processed.
 * <li>{@link Compensate} — saga enters {@link SagaStatus#COMPENSATED} after a single round
 * (single-shot compensation). {@code compensationEvents} are routed through the {@link
 * net.nexus_flow.core.outbox.OutboxStorage} for durable, at-least-once delivery to all
 * downstream consumers. Downstream sagas or handlers must be idempotent.
 * <li>{@link Fail} — saga goes directly to {@link SagaStatus#FAILED_TERMINAL} with {@code cause}
 * recorded for diagnostics and post-mortem analysis.
 * </ul>
 *
 * <p><strong>Atomic state transitions:</strong> {@link SagaRunner} ensures that each transition is
 * persisted atomically through optimistic concurrency checking. The saga instance is locked at the
 * storage layer during save, guaranteeing serialization.
 */
public sealed interface SagaTransition {

    /** Continue running with updated data; no compensation emitted. */
    record Continue(Map<String, Object> newData) implements SagaTransition {
        public Continue {
            Objects.requireNonNull(newData, "newData");
            newData = Map.copyOf(newData);
        }
    }

    /** Terminal success; no further events are processed. */
    record Complete(Map<String, Object> finalData) implements SagaTransition {
        public Complete {
            Objects.requireNonNull(finalData, "finalData");
            finalData = Map.copyOf(finalData);
        }
    }

    /**
     * Emit compensating events in a single batch; saga transitions directly to {@link
     * SagaStatus#COMPENSATED}. Compensation events are delivered durably through the outbox.
     */
    record Compensate(Map<String, Object> newData, List<DomainEvent> compensationEvents)
            implements SagaTransition {
        public Compensate(Map<String, Object> newData, List<DomainEvent> compensationEvents) {
            Objects.requireNonNull(newData, "newData");
            Objects.requireNonNull(compensationEvents, "compensationEvents");
            if (compensationEvents.isEmpty()) {
                throw new IllegalArgumentException(
                        "Compensate requires at least one compensation event; "
                                + "use Fail for a no-op terminal failure.");
            }
            for (DomainEvent e : compensationEvents) {
                Objects.requireNonNull(e, "compensationEvents must not contain null");
            }
            this.newData            = Map.copyOf(newData);
            this.compensationEvents = List.copyOf(compensationEvents);
        }
    }

    /** Terminal failure with cause; no compensation emitted. */
    record Fail(Throwable cause) implements SagaTransition {
        public Fail {
            Objects.requireNonNull(cause, "cause");
        }
    }
}
