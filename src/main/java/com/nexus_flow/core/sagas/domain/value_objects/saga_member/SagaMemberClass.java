package com.nexus_flow.core.sagas.domain.value_objects.saga_member;

import com.nexus_flow.core.ddd.exceptions.WrongFormat;
import com.nexus_flow.core.messaging.domain.DomainEvent;
import com.nexus_flow.core.sagas.domain.SagaCommand;
import com.nexus_flow.core.sagas.domain.exceptions.NotASagaObject;

import java.util.Objects;

public class SagaMemberClass {

    private Class<?> value;

    private SagaMemberClass() {
    }

    public SagaMemberClass(Class<?> value) {
        checkNotNull(value);
        checkIsEventOrCommand(value);
        this.value = value;
    }

    private void checkNotNull(Class<?> event) {
        if (event == null) {
            throw new WrongFormat(SagaMemberClass.class);
        }
    }

    private void checkIsEventOrCommand(Class<?> value) {
        if (!checkIsAnEvent(value) && !checkIsACommand(value)) {
            throw new NotASagaObject(value);
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

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SagaMemberClass that = (SagaMemberClass) o;
        return Objects.equals(value, that.value);
    }
}
