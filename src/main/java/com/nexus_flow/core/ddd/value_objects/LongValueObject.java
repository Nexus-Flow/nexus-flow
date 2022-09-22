package com.nexus_flow.core.ddd.value_objects;

import com.nexus_flow.core.ddd.exceptions.WrongFormat;

import java.util.Objects;

public abstract class LongValueObject {

    protected Long value;

    public LongValueObject(String value) {
        this.value = ensureIsAValidLong(value);
    }

    public LongValueObject(Long value) {
        this.value = value;
    }

    protected LongValueObject() {
    }

    private Long ensureIsAValidLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new WrongFormat(value + " is not a correct long");
        }
    }

    public Long getValue() {
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
        LongValueObject that = (LongValueObject) o;
        return Objects.equals(value, that.value);
    }
}
