package com.nexus_flow.core.sagas.domain.value_objects.saga_type;

import com.nexus_flow.core.ddd.exceptions.WrongFormat;
import com.nexus_flow.core.messaging.domain.DomainEvent;
import com.nexus_flow.core.sagas.domain.SagaCommand;
import com.nexus_flow.core.sagas.domain.exceptions.NotAValidTrigger;

import java.util.Objects;

public class TriggeredBy {

    private Class<?>    value;
    private TriggerType triggerType;

    private TriggeredBy() {
    }

    public TriggeredBy(Class<?> value) {
        checkNotNull(value);
        checkIsEventOrCommand(value);
        this.value = value;
    }

    private void checkNotNull(Class<?> event) {
        if (event == null) {
            throw new WrongFormat(TriggeredBy.class);
        }
    }

    private void checkIsEventOrCommand(Class<?> value) {
        if (checkIsAnEvent(value)) {
            this.triggerType = TriggerType.EVENT;
        } else if (checkIsACommand(value)) {
            this.triggerType = TriggerType.COMMAND;
        } else {
            throw new NotAValidTrigger();
        }
    }

    private boolean checkIsACommand(Class<?> value) {
        return SagaCommand.class.isAssignableFrom(value);
    }

    private boolean checkIsAnEvent(Class<?> value) {
        return DomainEvent.class.isAssignableFrom(value);
    }

    public Class<?> getValue() {
        return value;
    }

    public TriggerType getTriggerType() {
        return triggerType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, triggerType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TriggeredBy that = (TriggeredBy) o;
        return Objects.equals(value, that.value) &&
                triggerType == that.triggerType;
    }
}
