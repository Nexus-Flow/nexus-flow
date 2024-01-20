package org.nexusflow.core.cqrs.command;

import org.nexusflow.core.cqrs.reflection.TypeReference;

import java.time.Instant;
import java.util.UUID;

class CommandBuilder<T extends Record> {

    private UUID commandId;
    private Instant timestamp;
    private T body;
    private AcknowledgeMode ackMode;
    private int priority;
    private TypeReference<T> type;

    private CommandBuilder() {
    }

    public static <T extends Record> BodyStep<T> builder() {
        return new Steps<>();
    }

    public UUID getCommandId() {
        return commandId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public T getBody() {
        return body;
    }

    public AcknowledgeMode getAckMode() {
        return ackMode;
    }

    public int getPriority() {
        return priority;
    }

    public TypeReference<T> getType() {
        return type;
    }

    public interface BodyStep<T extends Record> {
        BuildStep<T> body(T body);
    }

    public interface BuildStep<T extends Record> {
        BuildStep<T> commandId(UUID commandId);

        BuildStep<T> timestamp(Instant timestamp);

        BuildStep<T> ackMode(AcknowledgeMode ackMode);

        BuildStep<T> priority(int priority);

        Command<T> build();
    }

    private static class Steps<T extends Record> implements BodyStep<T>, BuildStep<T> {
        private final CommandBuilder<T> builder = new CommandBuilder<>();

        @Override
        public BuildStep<T> body(T body) {
            builder.body = body;
            builder.type = new TypeReference<>(body.getClass());
            return this;
        }

        @Override
        public BuildStep<T> commandId(UUID commandId) {
            builder.commandId = commandId;
            return this;
        }

        @Override
        public BuildStep<T> timestamp(Instant timestamp) {
            builder.timestamp = timestamp;
            return this;
        }

        @Override
        public BuildStep<T> ackMode(AcknowledgeMode ackMode) {
            builder.ackMode = ackMode;
            return this;
        }

        @Override
        public BuildStep<T> priority(int priority) {
            builder.priority = priority;
            return this;
        }

        @Override
        public Command<T> build() {
            if (builder.body == null) {
                throw new IllegalStateException("Body cannot be null");
            }
            return new DefaultCommand<>(builder);
        }
    }

}