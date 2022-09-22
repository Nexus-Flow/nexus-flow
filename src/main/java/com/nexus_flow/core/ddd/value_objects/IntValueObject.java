package com.nexus_flow.core.ddd.value_objects;

import com.nexus_flow.core.ddd.exceptions.WrongFormat;

import java.util.Objects;

public abstract class IntValueObject {

    protected Integer value;

    protected IntValueObject(String value) {
        this.value = ensureIsAValidInteger(value);
    }

    protected IntValueObject(Integer value) {
        this.value = value;
    }

    protected IntValueObject() {
    }

    public Integer getValue() {
        return value;
    }

    protected void checkBetween(int min, int max) {
        if (this.value < min || this.value > max) {
            throw new WrongFormat(String.format("%d is not between %d and %d", this.value, min, max));
        }
    }

    private Integer ensureIsAValidInteger(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new WrongFormat(value + " is not a correct integer");
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
        IntValueObject that = (IntValueObject) o;
        return Objects.equals(value, that.value);
    }
}
