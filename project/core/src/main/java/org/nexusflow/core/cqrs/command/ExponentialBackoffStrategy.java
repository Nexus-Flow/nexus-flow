package org.nexusflow.core.cqrs.command;

import java.util.concurrent.TimeUnit;

public class ExponentialBackoffStrategy implements BackoffStrategy {
    private final long maxWaitMillis;
    private long waitMillis;
    private boolean inBackoffState;

    public ExponentialBackoffStrategy(long maxWaitMillis) {
        this.maxWaitMillis = maxWaitMillis;
        this.waitMillis = 1;
        this.inBackoffState = false;
    }

    @Override
    public void backoff() throws InterruptedException {
        if (waitMillis > 1) {
            inBackoffState = true;
        }
        TimeUnit.MILLISECONDS.sleep(waitMillis);
        waitMillis = Math.min(waitMillis * 2, maxWaitMillis);
    }

    @Override
    public boolean isInBackoffState() {
        return inBackoffState;
    }

}
