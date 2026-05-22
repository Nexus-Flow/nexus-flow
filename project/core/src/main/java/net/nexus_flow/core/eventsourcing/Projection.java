package net.nexus_flow.core.eventsourcing;

/**
 * Read-model builder fed by an {@link EventStore}.
 *
 * <p>A projection consumes envelopes in global-position order via a {@link ProjectionRunner}. Its
 * {@link #name()} identifies the persisted checkpoint (column PK in {@link
 * ProjectionCheckpointStore}); two projections with the same name share the same checkpoint and
 * therefore <strong>must not</strong> run concurrently against the same store.
 *
 * <p>{@link #apply(EventEnvelope)} is called sequentially by the runner with envelopes in strictly
 * increasing {@code globalPosition} order. Implementations need not be thread-safe — the runner
 * serializes calls — but they should be idempotent on duplicate applies in case of a crash between
 * {@link #apply(EventEnvelope)} and the checkpoint write.
 */
public interface Projection {

    /**
     * Identity / persisted checkpoint key.
     *
     * @return non-blank stable name used as the checkpoint key across restarts
     */
    String name();

    /**
     * Apply one envelope to the read model.
     *
     * @param envelope the envelope to apply; always in strictly increasing {@code globalPosition}
     *                 order
     */
    void apply(EventEnvelope envelope);

    /**
     * Current checkpoint — the {@code globalPosition} of the most recent {@link
     * #apply(EventEnvelope)} call.
     *
     * @return the last applied global position, or {@code 0} when nothing has been applied yet
     */
    long checkpoint();
}
