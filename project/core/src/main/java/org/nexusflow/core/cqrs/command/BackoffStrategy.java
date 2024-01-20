package org.nexusflow.core.cqrs.command;

interface BackoffStrategy {
    void backoff() throws InterruptedException;

    boolean isInBackoffState();
}
