package net.nexus_flow.core.cqrs.command;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.nexus_flow.core.runtime.ids.FastUuid;
import net.nexus_flow.core.types.TypeReference;
import org.jspecify.annotations.Nullable;

/**
 * Command envelope dispatched through the {@link CommandBus}.
 *
 * <p>A command carries its record body together with routing metadata such as priority, deadline,
 * headers, and a type token. The default builder creates immutable {@link DefaultCommand}
 * instances, while advanced integrations may provide their own implementations.
 *
 * @param <T> command payload type
 */
public sealed interface Command<T extends Record> extends Serializable permits DefaultCommand {

    /**
     * Creates a builder for immutable command envelopes.
     *
     * @param <T> command payload type
     * @return first builder step requiring the command body
     */
    static <T extends Record> CommandBuilder.BodyStep<T> builder() {
        return CommandBuilder.builder();
    }

    /**
     * Returns the command identifier.
     *
     * <p>The id is an observability handle (not a security token), so the default uses
     * {@link FastUuid#v4()} — wire-format-identical to {@link UUID#randomUUID()} but ~10×
     * cheaper at the hot-path call rate. The canonical {@link DefaultCommand} stamps a single
     * id at construction time and returns it on every call; this default is a fallback for
     * non-canonical implementations and is intentionally fast.
     *
     * @return command identifier
     */
    default UUID getCommandId() {
        return FastUuid.v4();
    }

    /**
     * Returns the command creation timestamp.
     *
     * @return creation timestamp
     */
    default Instant getTimestamp() {
        return Instant.now();
    }

    /**
     * Returns the command payload.
     *
     * @return command body record
     */
    T getBody();

    /**
     * Returns the scheduling priority used by queued executors.
     *
     * @return priority value; higher values run sooner
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Optional deadline by which this command must be executed.
     *
     * @return deadline instant, or {@code null} when no deadline is configured
     */
    default @Nullable Instant getDeadline() {
        return null;
    }

    /**
     * Returns arbitrary metadata headers associated with this command.
     *
     * @return immutable metadata headers, or an empty map when none were supplied
     */
    default Map<String, String> getHeaders() {
        return java.util.Collections.emptyMap();
    }

    /**
     * Returns the runtime routing token for this command body type.
     *
     * @return type reference used to locate the matching handler
     */
    TypeReference<T> getType();
}
