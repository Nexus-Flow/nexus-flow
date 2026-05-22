package net.nexus_flow.core.cqrs.query;

import java.time.Instant;
import java.util.UUID;
import net.nexus_flow.core.types.TypeReference;
import org.jspecify.annotations.Nullable;

class QueryBuilder<T extends Record> {

    private @Nullable UUID             queryId;
    private @Nullable Instant          timestamp;
    private @Nullable T                body;
    private @Nullable TypeReference<T> type;

    private QueryBuilder() {
    }

    /**
     * Creates a staged builder for a query envelope.
     *
     * @param <T> query body type
     * @return first builder step that captures the body
     */
    public static <T extends Record> BodyStep<T> builder() {
        return new Steps<>();
    }

    /**
     * Returns the explicit query identifier, if one was provided.
     *
     * @return configured query identifier or {@code null}
     */
    public @Nullable UUID getQueryId() {
        return queryId;
    }

    /**
     * Returns the explicit query timestamp, if one was provided.
     *
     * @return configured timestamp or {@code null}
     */
    public @Nullable Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the configured query body.
     *
     * @return query body or {@code null} before the body step completes
     */
    public @Nullable T getBody() {
        return body;
    }

    /**
     * Returns the inferred query body type token.
     *
     * @return type token or {@code null} before the body step completes
     */
    public @Nullable TypeReference<T> getType() {
        return type;
    }

    /**
     * First builder step that captures the query body.
     *
     * @param <T> query body type
     */
    @FunctionalInterface
    public interface BodyStep<T extends Record> {

        /**
         * Sets the query body.
         *
         * @param body immutable query payload
         * @return next builder step for optional metadata and final construction
         */
        BuildStep<T> body(T body);
    }

    /**
     * Final builder step for optional metadata and query creation.
     *
     * @param <T> query body type
     */
    public interface BuildStep<T extends Record> {

        /**
         * Sets an explicit query identifier.
         *
         * @param queryId query identifier to persist in the envelope
         * @return this builder step
         */
        BuildStep<T> queryId(UUID queryId);

        /**
         * Sets an explicit query timestamp.
         *
         * @param timestamp creation instant to persist in the envelope
         * @return this builder step
         */
        BuildStep<T> timestamp(Instant timestamp);

        /**
         * Builds the immutable query envelope.
         *
         * @return immutable query instance
         * @throws IllegalStateException if the body step was never completed
         */
        Query<T> build();
    }

    private static class Steps<T extends Record> implements BodyStep<T>, BuildStep<T> {
        private final QueryBuilder<T> builder = new QueryBuilder<>();

        /** {@inheritDoc} */
        @Override
        public BuildStep<T> body(T body) {
            builder.body = body;
            builder.type = new TypeReference<>(body.getClass());
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public BuildStep<T> queryId(UUID queryId) {
            builder.queryId = queryId;
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public BuildStep<T> timestamp(Instant timestamp) {
            builder.timestamp = timestamp;
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public Query<T> build() {
            if (builder.body == null) {
                throw new IllegalStateException("Body cannot be null");
            }
            return new DefaultQuery<>(builder);
        }
    }
}
