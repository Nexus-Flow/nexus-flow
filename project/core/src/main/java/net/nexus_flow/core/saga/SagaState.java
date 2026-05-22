package net.nexus_flow.core.saga;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Immutable snapshot of a saga instance at a point in time, persisted for restart safety.
 *
 * <p><strong>Optimistic concurrency control:</strong> {@link #version()} is the optimistic
 * concurrency token used by {@link SagaStorage#save(SagaState, long)}: a save with {@code
 * expectedVersion != state.version()} is rejected. The first insert uses {@code expectedVersion ==
 * 0}. This prevents lost-update anomalies when multiple processes attempt to write the same saga
 * instance concurrently.
 *
 * <p><strong>Opaque data map:</strong> {@link #data()} is an immutable map — sagas store their
 * domain-specific state here. The storage layer treats it as a blob and never inspects its
 * contents. The correlation key (if any) may be embedded in the data map as {@code
 * "_correlationKey"} for reference.
 *
 * <p><strong>Checkpoint for restart safety:</strong> {@link #lastProcessedGlobalPosition()} records
 * the global event store position of the last envelope the saga successfully processed. On process
 * restart, the runner resumes from this position, ensuring idempotency — already processed
 * envelopes are never replayed.
 *
 * <p><strong>Status lifecycle:</strong> A saga progresses through {@link SagaStatus} states from
 * {@link SagaStatus#RUNNING} to one of three terminal states ({@link SagaStatus#COMPLETED}, {@link
 * SagaStatus#COMPENSATED}, or {@link SagaStatus#FAILED_TERMINAL}). Once terminal, no further state
 * transitions occur.
 *
 * <h2>Why class, not record</h2>
 *
 * <p>Was a {@code record} until the same JMH evidence that drove {@code OutboxRecord}'s
 * conversion: the 6 {@code Objects.requireNonNull} + 2 range checks + 1 {@code isBlank} +
 * 2 {@code Map.copyOf} in the compact constructor cost ~5-10 ns per allocation, paid on every
 * saga transition ({@link #next}, {@link #withDeadline}, {@link #withStepCheckpoint},
 * {@link #withStepCheckpoints}). Each transition copies fields from {@code this} — already
 * validated when {@code this} was constructed — so re-running the same checks is wasted work.
 *
 * <p>The class shape exposes:
 *
 * <ul>
 * <li>A public canonical constructor that validates every argument — the externally-callable
 * shape, matches the previous record canonical constructor semantically.
 * <li>A package-private {@link #unchecked} static factory that skips validation. Transitions
 * use this path because every argument is either copied verbatim from {@code this} or
 * built inline against a known-valid base (e.g. {@code version + 1L}).
 * </ul>
 */
public final class SagaState {

    private final SagaId                      id;
    private final String                      type;
    private final SagaStatus                  status;
    private final long                        version;
    private final Map<String, Object>         data;
    private final Instant                     createdAt;
    private final Instant                     updatedAt;
    private final long                        lastProcessedGlobalPosition;
    private final @Nullable Instant           deadline;
    private final Map<String, StepCheckpoint> stepCheckpoints;

    /**
     * Public canonical constructor — validates every argument and defensively copies both maps.
     * Adapter modules and any external code that hand-constructs a {@link SagaState} go
     * through this constructor.
     */
    public SagaState(
            SagaId id,
            String type,
            SagaStatus status,
            long version,
            Map<String, Object> data,
            Instant createdAt,
            Instant updatedAt,
            long lastProcessedGlobalPosition,
            @Nullable Instant deadline,
            Map<String, StepCheckpoint> stepCheckpoints) {
        this.id   = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        this.status = Objects.requireNonNull(status, "status");
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0: " + version);
        }
        this.version   = version;
        this.data      = Map.copyOf(Objects.requireNonNull(data, "data"));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (lastProcessedGlobalPosition < 0) {
            throw new IllegalArgumentException(
                    "lastProcessedGlobalPosition must be >= 0: " + lastProcessedGlobalPosition);
        }
        this.lastProcessedGlobalPosition = lastProcessedGlobalPosition;
        this.deadline                    = deadline;
        this.stepCheckpoints             = Map.copyOf(Objects.requireNonNull(stepCheckpoints, "stepCheckpoints"));
    }

    /**
     * Private skeleton constructor — assigns fields without any validation or defensive copy.
     * Used by {@link #unchecked} for transition paths where every argument is sourced from
     * an already-validated {@link SagaState}.
     */
    private SagaState(
            SagaId id,
            String type,
            SagaStatus status,
            long version,
            Map<String, Object> data,
            Instant createdAt,
            Instant updatedAt,
            long lastProcessedGlobalPosition,
            @Nullable Instant deadline,
            Map<String, StepCheckpoint> stepCheckpoints,
            @SuppressWarnings("unused") boolean uncheckedMarker) {
        this.id                          = id;
        this.type                        = type;
        this.status                      = status;
        this.version                     = version;
        this.data                        = data;
        this.createdAt                   = createdAt;
        this.updatedAt                   = updatedAt;
        this.lastProcessedGlobalPosition = lastProcessedGlobalPosition;
        this.deadline                    = deadline;
        this.stepCheckpoints             = stepCheckpoints;
    }

    /**
     * Package-private fast-path factory — bypasses every {@code requireNonNull}, range check,
     * {@code isBlank} check and {@link Map#copyOf} defensive wrap. ONLY callable from inside
     * the {@code saga} package, and ONLY safe when every argument is either copied verbatim
     * from another already-validated {@link SagaState} or computed inline against a known-
     * valid base. Saves the per-transition validation cost the canonical constructor pays.
     */
    static SagaState unchecked(
            SagaId id,
            String type,
            SagaStatus status,
            long version,
            Map<String, Object> data,
            Instant createdAt,
            Instant updatedAt,
            long lastProcessedGlobalPosition,
            @Nullable Instant deadline,
            Map<String, StepCheckpoint> stepCheckpoints) {
        return new SagaState(
                id, type, status, version, data, createdAt, updatedAt,
                lastProcessedGlobalPosition, deadline, stepCheckpoints, true);
    }

    /**
     * Backwards-compatible 9-argument constructor preserving the shape that pre-dated the
     * typed {@code stepCheckpoints} field. Delegates with an empty checkpoint map.
     */
    public SagaState(
            SagaId id,
            String type,
            SagaStatus status,
            long version,
            Map<String, Object> data,
            Instant createdAt,
            Instant updatedAt,
            long lastProcessedGlobalPosition,
            @Nullable Instant deadline) {
        this(id,
             type,
             status,
             version,
             data,
             createdAt,
             updatedAt,
             lastProcessedGlobalPosition,
             deadline,
             Map.of());
    }

    /**
     * Backwards-compatible 8-argument constructor preserving the original (no-deadline, no-
     * checkpoints) record shape. Delegates with {@code deadline = null} and an empty
     * checkpoint map.
     */
    public SagaState(
            SagaId id,
            String type,
            SagaStatus status,
            long version,
            Map<String, Object> data,
            Instant createdAt,
            Instant updatedAt,
            long lastProcessedGlobalPosition) {
        this(id,
             type,
             status,
             version,
             data,
             createdAt,
             updatedAt,
             lastProcessedGlobalPosition,
             null,
             Map.of());
    }

    public SagaId id() {
        return id;
    }

    public String type() {
        return type;
    }

    public SagaStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public Map<String, Object> data() {
        return data;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long lastProcessedGlobalPosition() {
        return lastProcessedGlobalPosition;
    }

    public @Nullable Instant deadline() {
        return deadline;
    }

    public Map<String, StepCheckpoint> stepCheckpoints() {
        return stepCheckpoints;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SagaState other)) {
            return false;
        }
        return version == other.version && lastProcessedGlobalPosition == other.lastProcessedGlobalPosition && Objects.equals(id,
                                                                                                                              other.id) && Objects
                                                                                                                                      .equals(type,
                                                                                                                                              other.type) && status == other.status && Objects
                                                                                                                                                      .equals(data,
                                                                                                                                                              other.data) && Objects
                                                                                                                                                                      .equals(createdAt,
                                                                                                                                                                              other.createdAt) && Objects
                                                                                                                                                                                      .equals(updatedAt,
                                                                                                                                                                                              other.updatedAt) && Objects
                                                                                                                                                                                                      .equals(deadline,
                                                                                                                                                                                                              other.deadline) && Objects
                                                                                                                                                                                                                      .equals(stepCheckpoints,
                                                                                                                                                                                                                              other.stepCheckpoints);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, status, version, data, createdAt, updatedAt,
                            lastProcessedGlobalPosition, deadline, stepCheckpoints);
    }

    @Override
    public String toString() {
        return "SagaState["
                + "id=" + id
                + ", type=" + type
                + ", status=" + status
                + ", version=" + version
                + ", data=" + data
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
                + ", lastProcessedGlobalPosition=" + lastProcessedGlobalPosition
                + ", deadline=" + deadline
                + ", stepCheckpoints=" + stepCheckpoints
                + ']';
    }

    /** Create a fresh saga instance in {@link SagaStatus#RUNNING} state at version 0. */
    public static SagaState fresh(SagaId id, String type, Instant now) {
        return new SagaState(id, type, SagaStatus.RUNNING, 0L, Map.of(), now, now, 0L, null, Map.of());
    }

    /**
     * Create a fresh saga instance with a {@code deadline}. The {@link SagaRunner}'s timeout
     * sweeper transitions the saga to {@link SagaStatus#FAILED_TERMINAL} once
     * {@code now > deadline} AND status is still {@link SagaStatus#RUNNING}. Compute the
     * deadline relative to {@code now} at saga-instantiation time (e.g.
     * {@code now.plus(Duration.ofMinutes(30))}).
     */
    public static SagaState freshWithDeadline(SagaId id, String type, Instant now, Instant deadline) {
        Objects.requireNonNull(deadline, "deadline");
        return new SagaState(id, type, SagaStatus.RUNNING, 0L, Map.of(), now, now, 0L, deadline, Map.of());
    }

    /**
     * Derive a new state by advancing the version, status, checkpoint, and data. The {@code id},
     * {@code createdAt}, {@code deadline} and {@code stepCheckpoints} are immutable across
     * transitions (use {@link #withStepCheckpoint(StepCheckpoint)} explicitly to record a step
     * completion); the updated timestamp is set to {@code now}.
     *
     * @param newData       the updated saga data (typically from transition result maps)
     * @param newStatus     the next status ({@link SagaStatus#RUNNING}, {@link SagaStatus#COMPLETED},
     *                      etc.)
     * @param newCheckpoint the global event position of the envelope that triggered this transition
     * @param now           the current time (usually from a {@link java.time.Clock})
     * @return a new {@link SagaState} with version incremented by 1
     */
    public SagaState next(
            Map<String, Object> newData, SagaStatus newStatus, long newCheckpoint, Instant now) {
        // newData comes from the saga transition's domain logic — externally-supplied, must be
        // defensively copied so a mutation by the saga handler after handing it over does not
        // leak through into the persisted state.
        return unchecked(
                         id, type, newStatus, version + 1L, Map.copyOf(newData),
                         createdAt, now, newCheckpoint, deadline, stepCheckpoints);
    }

    /**
     * Return a copy of this state with the supplied {@code deadline}. Pass {@code null} to
     * clear an existing deadline. Useful when the saga's policy adjusts the deadline mid-flight
     * (e.g., after a successful step, extend the deadline by the next step's budget).
     */
    public SagaState withDeadline(@Nullable Instant newDeadline) {
        return unchecked(
                         id, type, status, version, data, createdAt, updatedAt,
                         lastProcessedGlobalPosition, newDeadline, stepCheckpoints);
    }

    /**
     * Return a copy of this state with {@code checkpoint} recorded under its {@link
     * StepCheckpoint#stepName()}. If a checkpoint with the same step name already exists, it
     * is replaced — the latest checkpoint wins (a step completing twice in the same saga is
     * either a re-execution after restart, in which case the fresher metadata is correct, or
     * an idempotent retry, in which case the value is equal anyway). Callers that need to
     * detect duplicate completion check {@link #hasStep(String)} BEFORE this call.
     *
     * @param checkpoint the step completion to record; must not be {@code null}
     * @return a new state with the checkpoint added; version, status, and data unchanged
     */
    public SagaState withStepCheckpoint(StepCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        java.util.LinkedHashMap<String, StepCheckpoint> nextMap =
                new java.util.LinkedHashMap<>(stepCheckpoints);
        nextMap.put(checkpoint.stepName(), checkpoint);
        return unchecked(
                         id, type, status, version, data, createdAt, updatedAt,
                         lastProcessedGlobalPosition, deadline, Map.copyOf(nextMap));
    }

    /**
     * Replace the entire checkpoint map. Useful when an adapter migrates checkpoints from an
     * earlier shape that stored them in {@link #data()}.
     *
     * @param newCheckpoints the replacement map; must not be {@code null}
     * @return a new state with checkpoints swapped
     */
    public SagaState withStepCheckpoints(Map<String, StepCheckpoint> newCheckpoints) {
        // External-supplied map — defensive copy at the boundary, then unchecked through to
        // the constructor (every other field is sourced from this).
        return unchecked(
                         id, type, status, version, data, createdAt, updatedAt,
                         lastProcessedGlobalPosition, deadline,
                         Map.copyOf(Objects.requireNonNull(newCheckpoints, "newCheckpoints")));
    }

    /** {@code true} iff the step has a checkpoint recorded (regardless of outcome). */
    public boolean hasStep(String stepName) {
        Objects.requireNonNull(stepName, "stepName");
        return stepCheckpoints.containsKey(stepName);
    }

    /** Return the checkpoint for {@code stepName}, or {@code null} if no such step ran. */
    public @Nullable StepCheckpoint stepCheckpoint(String stepName) {
        Objects.requireNonNull(stepName, "stepName");
        return stepCheckpoints.get(stepName);
    }

    /**
     * {@code true} iff this state carries a deadline AND the deadline is in the past relative
     * to {@code now} AND the saga is still {@link SagaStatus#RUNNING}. Terminal sagas are
     * never considered expired — once they hit a terminal status they are out of the runner's
     * scope.
     */
    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        return deadline != null && status == SagaStatus.RUNNING && now.isAfter(deadline);
    }
}
