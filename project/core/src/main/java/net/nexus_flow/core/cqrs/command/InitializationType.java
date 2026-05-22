package net.nexus_flow.core.cqrs.command;

/**
 * Startup mode for a handler executor's drainer threads.
 *
 * <p>{@link #EAGER} pre-starts the configured drainers when the handler is registered, while {@link
 * #LAZY} starts them on demand when work arrives.
 */
public enum InitializationType {
    EAGER,
    LAZY;

    /**
     * Indicates whether the executor should pre-start its drainers.
     *
     * @return {@code true} when this mode is {@link #EAGER}
     */
    public boolean isEager() {
        return this == EAGER;
    }

    /**
     * Indicates whether the executor should start drainers on demand.
     *
     * @return {@code true} when this mode is {@link #LAZY}
     */
    public boolean isLazy() {
        return this == LAZY;
    }
}
