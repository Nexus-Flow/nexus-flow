package net.nexus_flow.core.saga;

import java.time.Duration;
import java.util.Optional;
import net.nexus_flow.core.eventsourcing.EventEnvelope;

/**
 * Process-manager / saga contract. A saga observes events from an event store and reacts by
 * mutating its own opaque state, emitting compensations, or terminating.
 *
 * <p><strong>Pure function model:</strong> The saga is a pure function from {@code (envelope,
 * currentState)} to {@link SagaTransition}. The {@link SagaRunner} handles every side effect —
 * load/save state, route compensations through the outbox, advance the checkpoint.
 *
 * <p><strong>Type stability:</strong> {@link #type()} identifies all instances of this saga
 * uniformly; it is the persisted column in {@link SagaState#type()} and the routing key the runner
 * uses to dispatch envelopes to the right saga class.
 *
 * <p><strong>Correlation key extraction:</strong> {@link #correlationKeyFor(EventEnvelope)}
 * extracts the saga instance identity (typically the aggregate id) from the envelope. The runner
 * uses this key to locate (or create) the {@link SagaState} that should process the envelope.
 * Returning {@link Optional#empty()} skips the envelope — the saga does not care about it.
 *
 * <p><strong>State accumulation and compensation:</strong> As events arrive, the saga may update
 * its state (via {@link SagaTransition.Continue}), complete successfully ({{@link
 * SagaTransition.Complete}}), or trigger compensation ({{@link SagaTransition.Compensate}}) if an
 * earlier step fails. Compensation events are routed through the outbox for at-least-once durable
 * delivery.
 */
public interface Saga {

    /** Stable type name identifying every instance of this saga class. */
    String type();

    /**
     * Extract the correlation key (instance identity) from the event envelope.
     *
     * <p>Typically, this is the aggregate id that this saga instance is bound to. The runner uses
     * {@code (type, correlationKey)} as the persisted instance key.
     *
     * @param envelope the event envelope to inspect
     * @return non-empty if this envelope concerns the saga; {@link Optional#empty()} to skip
     */
    Optional<String> correlationKeyFor(EventEnvelope envelope);

    /**
     * Invoke the saga's business logic in response to an event.
     *
     * <p>This method is a pure function — it must not perform side effects (I/O, logging, etc). The
     * {@link SagaRunner} coordinates all side effects after inspecting the returned {@link
     * SagaTransition}.
     *
     * @param envelope     the event to process
     * @param currentState the saga's current state before applying this envelope
     * @return a {@link SagaTransition} indicating how to proceed
     */
    SagaTransition handle(EventEnvelope envelope, SagaState currentState);

    /**
     * Maximum wall-clock budget a fresh instance of this saga is allowed to spend in {@link
     * SagaStatus#RUNNING} before the {@link SagaRunner}'s timeout sweeper forces it to
     * {@link SagaStatus#FAILED_TERMINAL}.
     *
     * <p>Default: {@link Optional#empty()} — no timeout, the saga runs until it reaches a
     * terminal status on its own. Override and return a {@link Duration} for sagas whose
     * orchestration MUST eventually decide one way or the other (e.g., a payment saga that
     * must complete or refund within 30 minutes).
     *
     * <p>The deadline is computed relative to {@code state.createdAt()}: the saga state's
     * {@code deadline} field is set to {@code createdAt + timeout()} at instantiation time and
     * stays immutable across transitions (use {@link SagaState#withDeadline} from inside a
     * {@link SagaTransition} producer if the policy needs to extend or clear the deadline
     * mid-flight).
     */
    default Optional<Duration> timeout() {
        return Optional.empty();
    }
}
