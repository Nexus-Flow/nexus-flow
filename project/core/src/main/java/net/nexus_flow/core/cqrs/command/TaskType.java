package net.nexus_flow.core.cqrs.command;

/**
 * Logical work category tracked by {@link ThreadContext}.
 *
 * <p>The enum is shared across command, query, and event execution paths so diagnostics can report
 * a consistent task taxonomy.
 */
public enum TaskType {
    COMMAND,
    QUERY,
    EVENT
}
