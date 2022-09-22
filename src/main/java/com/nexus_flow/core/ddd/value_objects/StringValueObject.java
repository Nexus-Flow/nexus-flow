package com.nexus_flow.core.ddd.value_objects;


import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.ddd.exceptions.WrongFormat;
import org.apache.commons.lang3.StringUtils;

import java.util.Objects;

public abstract class StringValueObject {

    protected String value;

    protected StringValueObject(String value) {
        this.value = value;
    }

    protected StringValueObject() {
    }

    protected void checkNotExceeds(Integer maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new WrongFormat(Utils.toSnake(this.getClass().getSimpleName()) + " value <" + value + "> exceed max length [" + maxLength + "]");
        }
    }

    protected void checkLength(Integer length) {
        if (value != null && value.length() != length) {
            throw new WrongFormat(Utils.toSnake(this.getClass().getSimpleName()) + " value <" + value + "> does not match to length [" + length + "]");
        }
    }

    protected void checkNotNull() {
        if (value == null) {
            throw new WrongFormat(Utils.toSnake(this.getClass().getSimpleName()) + " can't be null");
        }
    }

    protected void checkNotNullAndNotEmpty() {
        if (StringUtils.isBlank(this.getValue())) {
            throw new WrongFormat(Utils.toSnake(this.getClass().getSimpleName()) + " can't be null or empty");
        }
    }

    protected void checkPattern(String pattern) {
        checkNotNull();
        if (!this.value.matches(pattern)) {
            throw new WrongFormat(Utils.toSnake(this.getClass().getSimpleName()) + " Value <" + this.value + "> has not the pattern " + pattern);
        }
    }

    protected void trimValue() {
        value = value.trim();
    }

    public boolean checkIsBlank() {
        return value == null || value.isEmpty();
    }

    public String getValue() {
        return value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StringValueObject)) {
            return false;
        }
        StringValueObject that = (StringValueObject) o;
        return Objects.equals(value, that.value);
    }

    @Override
    public String toString() {
        return this.value;
    }
}
