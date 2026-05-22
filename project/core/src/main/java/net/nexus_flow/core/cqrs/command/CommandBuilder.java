package net.nexus_flow.core.cqrs.command;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.nexus_flow.core.types.TypeReference;
import org.jspecify.annotations.Nullable;

class CommandBuilder<T extends Record> {

    private @Nullable UUID             commandId;
    private @Nullable Instant          timestamp;
    private @Nullable T                body;
    private int                        priority;
    private @Nullable Instant          deadline;
    private final Map<String, String>  headers = new LinkedHashMap<>();
    private @Nullable TypeReference<T> type;

    private CommandBuilder() {
    }

    /**
     * Creates a builder for immutable commands.
     *
     * @param <T> command payload type
     * @return first builder step requiring the body
     */
    public static <T extends Record> BodyStep<T> builder() {
        return new Steps<>();
    }

    /**
     * Returns the configured command id, if any.
     *
     * @return configured id, or {@code null} when the default should be generated
     */
    public @Nullable UUID getCommandId() {
        return commandId;
    }

    /**
     * Returns the configured timestamp, if any.
     *
     * @return configured timestamp, or {@code null} when the default should be generated
     */
    public @Nullable Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the configured body.
     *
     * @return command body, or {@code null} until assigned
     */
    public @Nullable T getBody() {
        return body;
    }

    /**
     * Returns the configured priority.
     *
     * @return command priority
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Returns the configured deadline.
     *
     * @return deadline, or {@code null} when unset
     */
    public @Nullable Instant getDeadline() {
        return deadline;
    }

    /**
     * Returns the configured headers.
     *
     * @return mutable header map held by the builder
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * Returns the configured command type token.
     *
     * @return type token, or {@code null} until the body is assigned
     */
    public @Nullable TypeReference<T> getType() {
        return type;
    }

    /** First builder step that captures the command body. */
    @FunctionalInterface
    public interface BodyStep<T extends Record> {

        /**
         * Stores the command payload and infers its routing type.
         *
         * @param body command payload
         * @return next builder step
         */
        BuildStep<T> body(T body);
    }

    /** Remaining builder steps for optional metadata and final command creation. */
    public interface BuildStep<T extends Record> {

        /**
         * Overrides the command id.
         *
         * @param commandId explicit command id
         * @return this builder step
         */
        BuildStep<T> commandId(UUID commandId);

        /**
         * Overrides the command timestamp.
         *
         * @param timestamp explicit creation timestamp
         * @return this builder step
         */
        BuildStep<T> timestamp(Instant timestamp);

        /**
         * Sets the command priority.
         *
         * @param priority priority value; higher values run sooner
         * @return this builder step
         */
        BuildStep<T> priority(int priority);

        /**
         * Sets the command deadline.
         *
         * @param deadline execution deadline
         * @return this builder step
         */
        BuildStep<T> deadline(Instant deadline);

        /**
         * Adds a single command header.
         *
         * @param key   header name
         * @param value header value
         * @return this builder step
         */
        BuildStep<T> header(String key, String value);

        /**
         * Adds a batch of command headers.
         *
         * @param headers headers to merge into the command
         * @return this builder step
         */
        BuildStep<T> headers(Map<String, String> headers);

        /**
         * Creates the immutable command envelope.
         *
         * @return immutable command instance
         */
        Command<T> build();
    }

    private static class Steps<T extends Record> implements BodyStep<T>, BuildStep<T> {
        private final CommandBuilder<T> builder = new CommandBuilder<>();

        /** {@inheritDoc} */
        @Override
        public BuildStep<T> body(T body) {
            T commandBody = Objects.requireNonNull(body, "body");
            builder.body = commandBody;
            builder.type = new TypeReference<>(commandBody.getClass());
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public BuildStep<T> commandId(UUID commandId) {
            builder.commandId = Objects.requireNonNull(commandId, "commandId");
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public BuildStep<T> timestamp(Instant timestamp) {
            builder.timestamp = Objects.requireNonNull(timestamp, "timestamp");
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public BuildStep<T> priority(int priority) {
            builder.priority = priority;
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public BuildStep<T> deadline(Instant deadline) {
            builder.deadline = Objects.requireNonNull(deadline, "deadline");
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public BuildStep<T> header(String key, String value) {
            builder.headers.put(
                                Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public BuildStep<T> headers(Map<String, String> headers) {
            builder.headers.putAll(Objects.requireNonNull(headers, "headers"));
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public Command<T> build() {
            if (builder.body == null) {
                throw new IllegalStateException("Body cannot be null");
            }
            return new DefaultCommand<>(builder);
        }
    }
}
