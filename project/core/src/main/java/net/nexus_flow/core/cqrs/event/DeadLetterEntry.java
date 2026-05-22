package net.nexus_flow.core.cqrs.event;

import java.time.Instant;
import net.nexus_flow.core.ddd.DomainEvent;

/**
 * Captures a failed event dispatch after all {@link RetryPolicy} attempts have been exhausted and
 * the {@link EventListenerErrorHandler} (if any) has also failed or is absent.
 *
 * @param event         the event that could not be processed successfully
 * @param listenerClass the listener class that ultimately failed the event
 * @param cause         the terminal failure that caused the event to be dead-lettered
 * @param occurredAt    the timestamp at which the dead-letter entry was created
 * @param totalAttempts the total number of attempts made before dead-lettering
 */
public record DeadLetterEntry(
                              DomainEvent event,
                              Class<?> listenerClass,
                              Throwable cause,
                              Instant occurredAt,
                              int totalAttempts) {

    /**
     * Creates a validated dead-letter entry.
     *
     * @throws NullPointerException     if any reference component is {@code null}
     * @throws IllegalArgumentException if {@code totalAttempts < 1}
     */
    public DeadLetterEntry {
        java.util.Objects.requireNonNull(event, "event");
        java.util.Objects.requireNonNull(listenerClass, "listenerClass");
        java.util.Objects.requireNonNull(cause, "cause");
        java.util.Objects.requireNonNull(occurredAt, "occurredAt");
        if (totalAttempts < 1)
            throw new IllegalArgumentException("totalAttempts must be >= 1");
    }
}
