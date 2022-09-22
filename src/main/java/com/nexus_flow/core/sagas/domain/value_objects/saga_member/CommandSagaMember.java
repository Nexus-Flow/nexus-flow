package com.nexus_flow.core.sagas.domain.value_objects.saga_member;

import com.nexus_flow.core.ddd.exceptions.WrongFormat;
import com.nexus_flow.core.sagas.domain.SagaCommand;

import java.util.Objects;

public class CommandSagaMember implements SagaMemberPayload {

    private SagaCommand value;

    public CommandSagaMember() {
    }

    public CommandSagaMember(SagaCommand value) {
        checkNotNull(value);
        this.value = value;
    }

    private void checkNotNull(SagaCommand event) {
        if (event == null) {
            throw new WrongFormat(CommandSagaMember.class);
        }
    }

    public SagaCommand getValue() {
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
        CommandSagaMember that = (CommandSagaMember) o;
        return Objects.equals(value, that.value);
    }
}
