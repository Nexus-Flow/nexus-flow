package net.nexus_flow.core.cqrs.command;

import java.util.Objects;
import net.nexus_flow.core.runtime.ErrorPolicy;
import org.jspecify.annotations.Nullable;

/**
 * Configuration captured by command handlers created from integration-layer adapters rather than
 * concrete {@code Abstract*CommandHandler} subclasses.
 *
 * <p>The record is deliberately constructible from primitive values and other public settings
 * records so container integrations can bind it from external configuration properties without
 * reaching into package-private types.
 *
 * @param priority           handler queue priority
 * @param concurrencyLevel   maximum in-flight handler executions; {@code 0} means inline execution
 * @param initializationType eager vs. lazy drainer startup mode
 * @param sagaEnabled        whether emitted events should be dispatched with saga semantics
 * @param commandSettings    additional per-handler execution settings
 * @param defaultErrorPolicy optional handler-level default error policy
 */
public record CommandHandlerOptions(
                                    int priority,
                                    int concurrencyLevel,
                                    InitializationType initializationType,
                                    boolean sagaEnabled,
                                    CommandSettings commandSettings,
                                    @Nullable ErrorPolicy defaultErrorPolicy) {

    public static final CommandHandlerOptions DEFAULTS =
            new CommandHandlerOptions(0, 0, InitializationType.LAZY, false, new CommandSettings(), null);

    /**
     * Creates validated handler options.
     *
     * @throws IllegalArgumentException if {@code concurrencyLevel} is negative
     * @throws NullPointerException     if {@code initializationType} or {@code commandSettings} is null
     */
    public CommandHandlerOptions {
        if (concurrencyLevel < 0) {
            throw new IllegalArgumentException("concurrencyLevel must be >= 0, got: " + concurrencyLevel);
        }
        Objects.requireNonNull(initializationType, "initializationType");
        Objects.requireNonNull(commandSettings, "commandSettings");
    }
}
