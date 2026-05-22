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
        // Stack-traceless wrapper — the cause already carries the listener-body stack trace.
        // This exception is unwrapped immediately by SyncDispatcher.classify so the wrapper's
        // trace would only point to the lambda boundary, not the failing listener. Suppression
        // chain remains active. Saves ~200 ns per listener invocation that throws.
        // Preserve the {@code super(Throwable)} message contract — getMessage() returns
        // cause.toString() and not null.
        super(
              cause == null ? null : cause.toString(),
              cause,
              /* enableSuppression= */ true,
              /* writableStackTrace= */ false);
    }
}
