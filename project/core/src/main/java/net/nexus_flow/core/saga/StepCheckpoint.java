package net.nexus_flow.core.saga;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Durable record of a single step's completion inside a long-running saga. Promoted from the
 * untyped {@link SagaState#data()} map to a typed first-class field so the runtime — and
 * adapter modules — can query, evolve and reason about step-level progress without parsing
 * map values.
 *
 * <h2>Why a dedicated type</h2>
 *
 * A saga that interleaves "reserve inventory → charge payment → ship → notify" needs to
 * persist WHICH steps have succeeded so that, after a restart, the runner does not re-execute
 * already-completed steps. The earlier shape encoded this as ad-hoc keys in {@code
 * SagaState#data()} — workable but type-erased, easy to typo, and impossible for adapters
 * (Spring, Quarkus, JDBC) to index without parsing the entire data map.
 *
 * <h2>Wire layout</h2>
 *
 * <ul>
 * <li>{@code stepName} — stable string identifier of the step. By convention upper-snake-case
 * (e.g. {@code "RESERVE_INVENTORY"}, {@code "CHARGE_PAYMENT"}). Must not be blank.
 * <li>{@code completedAt} — wall-clock instant when the step transitioned to success.
 * <li>{@code outcome} — small string discriminator the saga assigns. Default
 * {@link #OUTCOME_SUCCESS}. Saga code that supports multi-outcome steps (success vs
 * skipped vs compensated) sets the value here so a restart can branch without
 * re-running expensive logic.
 * <li>{@code attributes} — small immutable map for step-specific metadata (e.g. external
 * reservation id, gateway transaction id). Kept tiny on purpose — durable persistence
 * is the expensive part; lift bulk artefacts into the aggregate or event store, not
 * the saga state.
 * </ul>
 *
 * @param stepName    non-blank step identifier
 * @param completedAt wall-clock instant when the step finished
 * @param outcome     small discriminator, defaults to {@link #OUTCOME_SUCCESS}
 * @param attributes  immutable metadata map; never {@code null}, may be empty
 */
public record StepCheckpoint(
                             String stepName,
                             Instant completedAt,
                             String outcome,
                             Map<String, String> attributes) {

    /** Canonical outcome for a successful step. */
    public static final String OUTCOME_SUCCESS = "SUCCESS";

    /** Canonical outcome for a step intentionally skipped (e.g. conditional branch). */
    public static final String OUTCOME_SKIPPED = "SKIPPED";

    /** Canonical outcome for a step whose compensation has run (saga rollback path). */
    public static final String OUTCOME_COMPENSATED = "COMPENSATED";

    public StepCheckpoint {
        Objects.requireNonNull(stepName, "stepName");
        if (stepName.isBlank()) {
            throw new IllegalArgumentException("stepName must not be blank");
        }
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(outcome, "outcome");
        if (outcome.isBlank()) {
            throw new IllegalArgumentException("outcome must not be blank");
        }
        Objects.requireNonNull(attributes, "attributes");
        attributes = Map.copyOf(attributes);
    }

    /**
     * Convenience factory: a checkpoint with {@link #OUTCOME_SUCCESS} and no attributes.
     */
    public static StepCheckpoint success(String stepName, Instant completedAt) {
        return new StepCheckpoint(stepName, completedAt, OUTCOME_SUCCESS, Map.of());
    }

    /**
     * Read a single attribute, or {@code null} if the key is absent. Callers that need
     * non-null defaults wrap with {@link Map#getOrDefault(Object, Object)} on the map
     * returned by {@link #attributes()}.
     */
    public @Nullable String attribute(String key) {
        Objects.requireNonNull(key, "key");
        return attributes.get(key);
    }
}
