package net.nexus_flow.core.runtime.result;

import java.io.Serial;
import java.time.Instant;
import java.util.Objects;
import net.nexus_flow.core.runtime.ExecutionContext;

/** Thrown when a dispatch's {@link ExecutionContext#deadline()} has elapsed before completion. */
public final class FlowDeadlineExceededException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Instant deadline;

    /**
     * Creates a deadline-exceeded exception recording the absolute deadline that was missed.
     *
     * @param deadline the absolute {@link Instant} that was set as the deadline; never {@code null}
     * @throws NullPointerException if {@code deadline} is {@code null}
     */
    public FlowDeadlineExceededException(Instant deadline) {
        super("Deadline exceeded at " + Objects.requireNonNull(deadline, "deadline"));
        this.deadline = deadline;
    }

    /**
     * Returns the absolute deadline {@link Instant} that caused this exception.
     *
     * @return the deadline; never {@code null}
     */
    public Instant deadline() {
        return deadline;
    }
}
