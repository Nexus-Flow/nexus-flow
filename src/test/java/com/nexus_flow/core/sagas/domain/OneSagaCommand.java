package com.nexus_flow.core.sagas.domain;

public class OneSagaCommand extends SagaCommand {

    public OneSagaCommand(String commandId, String sagaTriggeredOn) {
        super(commandId, sagaTriggeredOn);
    }

}
