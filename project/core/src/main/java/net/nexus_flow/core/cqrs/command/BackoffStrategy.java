package net.nexus_flow.core.cqrs.command;

interface BackoffStrategy {
    void backoff() throws InterruptedException;

    boolean isInBackoffState();
}
