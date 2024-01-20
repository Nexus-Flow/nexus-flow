package org.nexusflow.core.cqrs.query;

import org.nexusflow.core.cqrs.reflection.TypeReference;

import java.time.Instant;
import java.util.UUID;

class QueryBuilder<T extends Record> {

    private UUID queryId;
    private Instant timestamp;
    private T body;
    private TypeReference<T> type;

    private QueryBuilder() {
    }

    public static <T extends Record> BodyStep<T> builder() {
        return new Steps<>();
    }

    public UUID getQueryId() {
        return queryId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public T getBody() {
        return body;
    }

    public TypeReference<T> getType() {
        return type;
    }

    public interface BodyStep<T extends Record> {
        BuildStep<T> body(T body);
    }

    public interface BuildStep<T extends Record> {
        BuildStep<T> queryId(UUID queryId);

        BuildStep<T> timestamp(Instant timestamp);

        Query<T> build();
    }

    private static class Steps<T extends Record> implements BodyStep<T>, BuildStep<T> {
        private final QueryBuilder<T> builder = new QueryBuilder<>();

        @Override
        public BuildStep<T> body(T body) {
            builder.body = body;
            builder.type = new TypeReference<>(body.getClass());
            return this;
        }

        @Override
        public BuildStep<T> queryId(UUID queryId) {
            builder.queryId = queryId;
            return this;
        }

        @Override
        public BuildStep<T> timestamp(Instant timestamp) {
            builder.timestamp = timestamp;
            return this;
        }

        @Override
        public Query<T> build() {
            if (builder.body == null) {
                throw new IllegalStateException("Body cannot be null");
            }
            return new DefaultQuery<>(builder);
        }
    }

}