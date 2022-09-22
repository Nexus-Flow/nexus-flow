package com.nexus_flow.core.sagas.domain.value_objects.saga_type;

import com.nexus_flow.core.ddd.exceptions.WrongFormat;

import java.time.Duration;
import java.util.Objects;

public class TimeOut {

    private Duration value;

    private TimeOut() {
    }

    public TimeOut(Duration value) {
        this.value = value;
        checkNotNull();
    }

    public Duration getValue() {
        return value;
    }

    protected void checkNotNull() {
        if (value == null) {
            throw new WrongFormat(this.getClass());
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TimeOut timeOut = (TimeOut) o;
        return Objects.equals(value, timeOut.value);
    }
}
