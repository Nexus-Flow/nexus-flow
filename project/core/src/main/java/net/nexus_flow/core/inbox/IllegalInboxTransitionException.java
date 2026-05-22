package net.nexus_flow.core.inbox;

import java.io.Serial;

/**
 * Thrown when a caller attempts an illegal state transition on an {@link InboxRecord}.
 *
 * <p>Typical triggers:
 *
 * <ul>
 * <li>Calling {@link InboxStorage#markProcessed} or {@link InboxStorage#markFailed} on a row that
 * is already in the {@link InboxStatus#PROCESSED} terminal state.
 * <li>Calling any transition method with an {@link InboxId} that does not exist in storage.
 * </ul>
 *
 * <p>This is an unchecked exception because these conditions represent programming errors: the
 * owner of a {@link InboxClaim.Fresh} result is the only party that should call transition methods,
 * and only once per claim.
 */
public final class IllegalInboxTransitionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception with a message describing the illegal transition.
     *
     * @param message human-readable description of the violation, including the {@link InboxId} and
     *                the current {@link InboxStatus}
     */
    public IllegalInboxTransitionException(String message) {
        super(message);
    }
}
