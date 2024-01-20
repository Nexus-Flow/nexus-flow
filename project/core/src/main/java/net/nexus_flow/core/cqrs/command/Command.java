package net.nexus_flow.core.cqrs.command;

import net.nexus_flow.core.cqrs.reflection.TypeReference;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public sealed interface Command<T extends Record> extends Serializable permits DefaultCommand {

    // Static factory method to obtain a builder instance
    static <T extends Record> CommandBuilder.BodyStep<T> builder() {
        return CommandBuilder.builder();
    }

    default UUID getCommandId() {
        return UUID.randomUUID();
    }

    default Instant getTimestamp() {
        return Instant.now();
    }

    T getBody();

    default AcknowledgeMode getAckMode() {
        return AcknowledgeMode.AUTO;
    }

    default int getPriority() {
        return 0;
    }

    TypeReference<T> getType();

}
