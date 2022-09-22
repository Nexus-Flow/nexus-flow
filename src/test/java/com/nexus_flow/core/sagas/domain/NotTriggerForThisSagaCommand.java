package com.nexus_flow.core.sagas.domain;

public class NotTriggerForThisSagaCommand extends SagaCommand {


    public NotTriggerForThisSagaCommand(String commandId, String sagaTriggeredOn) {
        super(commandId, sagaTriggeredOn);
    }


}
