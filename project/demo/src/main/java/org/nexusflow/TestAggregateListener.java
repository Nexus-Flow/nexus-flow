package org.nexusflow;

import org.nexusflow.core.cqrs.event.AbstractDomainEventListener;

import java.util.logging.Logger;

public class TestAggregateListener extends AbstractDomainEventListener<UpdateTestDomainEvent> {

    private static final Logger logger = Logger.getLogger(TestAggregateListener.class.getName());

    @Override
    public void handle(UpdateTestDomainEvent event) {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        logger.info("UpdateTestDomainEvent processed");
    }

    @Override
    public int order() {
        return 1;
    }
}