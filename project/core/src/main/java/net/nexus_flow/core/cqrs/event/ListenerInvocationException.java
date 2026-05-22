package net.nexus_flow.core.cqrs.event;

import java.io.Serial;

/**
 * Package-private carrier that wraps a non-{@link RuntimeException} thrown by a listener body
 * inside a lambda that cannot declare checked exceptions.
 *
 * <p>Never escapes the event-bus dispatch path; always unwrapped by the outer {@code catch
 * (Throwable)} that feeds {@code SyncDispatcher.classify}.
 */
final class ListenerInvocationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    ListenerInvocationException(Throwable cause) {
        super(cause);
    }
}
