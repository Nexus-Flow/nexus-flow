package com.nexus_flow.core.ddd.value_objects;

import com.nexus_flow.core.ddd.exceptions.WrongFormat;

import java.util.Objects;

public abstract class PercentageValueObject {

    protected Integer value;

    public PercentageValueObject(Integer value) {
        ensureValidPercentage(value);
        this.value = value;
    }

    private void ensureValidPercentage(Integer value) {
        if (value != null && (value < 0 || value > 100)) {
            throw new WrongFormat("Percentage <" + value + "> has not a valid value.");
        }
    }

    protected PercentageValueObject() {
    }

    public Integer getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PercentageValueObject that = (PercentageValueObject) o;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
