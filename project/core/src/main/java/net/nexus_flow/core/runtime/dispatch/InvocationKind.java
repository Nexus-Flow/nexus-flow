package net.nexus_flow.core.runtime.dispatch;

/**
 * Classification of the dispatch being intercepted.
 *
 * <p>Carried by {@link InvocationContext}; interceptors use it to switch behaviour (e.g. log a
 * different tag for queries) without sniffing the payload's concrete class.
 */
public enum InvocationKind {
    /** Command dispatch (fire-and-forget or request/response). */
    COMMAND,
    /** Synchronous query dispatch. */
    QUERY,
    /** Domain-event publication. */
    EVENT
}
