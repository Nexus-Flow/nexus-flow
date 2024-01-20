package net.nexus_flow.core.cqrs.command;

import net.nexus_flow.core.cqrs.reflection.TypeReference;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

// TODO: This class is package-private for now as there is only one implementation
non-sealed class DefaultCommand<T extends Record> implements Command<T> {
    private final UUID commandId;
    private final Instant timestamp;
    private final T body;
    private final AcknowledgeMode ackMode;
    private final int priority;
    private final TypeReference<T> type;

    protected DefaultCommand(CommandBuilder<T> builder) {
        this.commandId = builder.getCommandId() != null ? builder.getCommandId() : UUID.randomUUID();
        this.timestamp = builder.getTimestamp() != null ? builder.getTimestamp() : Instant.now();
        this.body = builder.getBody();
        this.ackMode = builder.getAckMode() != null ? builder.getAckMode() : AcknowledgeMode.AUTO;
        this.priority = builder.getPriority();
        this.type = builder.getType();
    }

    @Override
    public UUID getCommandId() {
        return commandId;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public T getBody() {
        return body;
    }

    @Override
    public AcknowledgeMode getAckMode() {
        return ackMode;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public TypeReference<T> getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DefaultCommand<?> that)) return false;
        return Objects.equals(getCommandId(), that.getCommandId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getCommandId());
    }

}
