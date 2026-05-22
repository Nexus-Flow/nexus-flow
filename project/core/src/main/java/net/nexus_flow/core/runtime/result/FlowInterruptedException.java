package net.nexus_flow.core.runtime.result;

import java.io.Serial;

/**
 * Thrown when a framework operation is interrupted via {@link Thread#interrupt()}.
 *
 * <p>The interrupted status is always restored on the current thread before this exception is
 * thrown.
 */
public final class FlowInterruptedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates a flow-interrupted exception wrapping the original {@link InterruptedException}.
     *
     * @param message human-readable description of the interrupted operation
     * @param cause   the original {@link InterruptedException}; never {@code null}
     */
    public FlowInterruptedException(String message, InterruptedException cause) {
        super(message, cause);
    }
}
