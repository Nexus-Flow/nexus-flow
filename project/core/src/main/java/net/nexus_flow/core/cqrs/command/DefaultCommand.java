package net.nexus_flow.core.cqrs.command;

import java.io.Serial;
import java.time.Instant;
import java.util.*;
import net.nexus_flow.core.runtime.ids.FastUuid;
import net.nexus_flow.core.types.TypeReference;
import org.jspecify.annotations.Nullable;

// package-private: callers use the Command.builder() factory
non-sealed class DefaultCommand<T extends Record> implements Command<T> {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID    commandId;
    private final Instant timestamp;

    /**
     * The command body is a user-supplied {@code Record}; its serviceability depends on the user's
     * type. Marked {@code transient} because commands are dispatched in-process and are never
     * marshaled across JVM restarts.
     */
    private final transient T body;

    private final int               priority;
    private final @Nullable Instant deadline;

    /**
     * Marked {@code transient}: commands are dispatched in-process; headers are runtime routing
     * metadata that must not be serialized across JVM restarts.
     */
    private final transient Map<String, String> headers;

    /** Runtime routing token — process-scoped, never serialized. */
    private final transient TypeReference<T> type;

    /**
     * Creates an immutable command from the populated builder state.
     *
     * @param builder populated builder instance
     */
    protected DefaultCommand(CommandBuilder<T> builder) {
        CommandBuilder<T> source = Objects.requireNonNull(builder, "builder");
        this.commandId = source.getCommandId() != null ? source.getCommandId() : FastUuid.v4();
        this.timestamp = source.getTimestamp() != null ? source.getTimestamp() : Instant.now();
        this.body      = Objects.requireNonNull(source.getBody(), "body");
        this.priority  = source.getPriority();
        this.deadline  = source.getDeadline();
        Map<String, String> sourceHeaders = source.getHeaders();
        this.headers = sourceHeaders.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(sourceHeaders));
        this.type    = Objects.requireNonNull(source.getType(), "type");
    }

    /** {@inheritDoc} */
    @Override
    public UUID getCommandId() {
        return commandId;
    }

    /** {@inheritDoc} */
    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    /** {@inheritDoc} */
    @Override
    public T getBody() {
        return body;
    }

    /** {@inheritDoc} */
    @Override
    public int getPriority() {
        return priority;
    }

    /** {@inheritDoc} */
    @Override
    public @Nullable Instant getDeadline() {
        return deadline;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, String> getHeaders() {
        return headers;
    }

    /** {@inheritDoc} */
    @Override
    public TypeReference<T> getType() {
        return type;
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof DefaultCommand<?> that))
            return false;
        return Objects.equals(getCommandId(), that.getCommandId());
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return Objects.hash(getCommandId());
    }
}
