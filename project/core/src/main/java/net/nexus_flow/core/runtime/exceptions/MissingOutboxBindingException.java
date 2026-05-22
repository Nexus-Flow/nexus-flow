package net.nexus_flow.core.runtime.exceptions;

import java.io.Serial;

/**
 * Thrown when an {@code ExecutionMode} that requires durable infrastructure (typically {@code
 * ExecutionMode.AsynchronousDurable}) is resolved against a {@code FlowRuntime} that has no outbox
 * binding configured.
 *
 * <p>This is a configuration mistake and never a runtime fault — once the outbox binding is added
 * via {@code FlowRuntime.builder().outbox(...).build()} the dispatch will succeed.
 *
 * <p>Extends {@link IllegalStateException} for catch compatibility with the idiomatic Java type.
 */
public final class MissingOutboxBindingException extends IllegalStateException {
    @Serial
    private static final long serialVersionUID = 1L;

    /** Creates the default missing-outbox-binding exception with the full guidance message. */
    public MissingOutboxBindingException() {
        super(
              "ExecutionMode.AsynchronousDurable requires an outbox binding on the FlowRuntime. Configure"
                      + " one via FlowRuntime.builder().outbox(OutboxConfig.builder(...)...).build(). Without"
                      + " an outbox the durable contract (append-then-publish, at-least-once cross-boundary"
                      + " delivery) cannot be honoured.");
    }

    /**
     * Creates a missing-outbox exception with a custom message.
     *
     * @param message human-readable description; never {@code null}
     */
    public MissingOutboxBindingException(String message) {
        super(message);
    }
}
