package com.nexus_flow.core.sagas.domain;

import com.nexus_flow.core.cqrs.domain.command.Command;

import java.util.Objects;

public abstract class SagaCommand implements Command {

    private String commandId;

    private String sagaTriggeredOn;


    protected SagaCommand(String commandId, String sagaTriggeredOn) {
        this.commandId       = this.getClass().getSimpleName() + "#" + commandId;
        this.sagaTriggeredOn = sagaTriggeredOn;
    }

    public String getCommandId() {
        return commandId;
    }

    public String getSagaTriggeredOn() {
        return sagaTriggeredOn;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SagaCommand that = (SagaCommand) o;
        return Objects.equals(commandId, that.commandId) &&
                Objects.equals(sagaTriggeredOn, that.sagaTriggeredOn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commandId, sagaTriggeredOn);
    }
}
