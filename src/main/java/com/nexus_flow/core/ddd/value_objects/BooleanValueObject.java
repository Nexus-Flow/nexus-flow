package com.nexus_flow.core.ddd.value_objects;

import java.util.Objects;

public abstract class BooleanValueObject {

    protected boolean value;

    public BooleanValueObject(boolean value) {
        this.value = value;
    }

    protected BooleanValueObject() {

    }

    public boolean getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BooleanValueObject)) return false;
        BooleanValueObject that = (BooleanValueObject) o;
        return getValue() == that.getValue();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getValue());
    }

    @Override
    public String toString() {
        return "BooleanValueObject{" +
                "value=" + value +
                '}';
    }
}
