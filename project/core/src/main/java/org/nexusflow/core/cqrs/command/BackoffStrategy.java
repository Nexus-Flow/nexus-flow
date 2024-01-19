package org.nexusflow.core.cqrs.command;

public interface BackoffStrategy {
    void backoff() throws InterruptedException;

    boolean isInBackoffState();
}
