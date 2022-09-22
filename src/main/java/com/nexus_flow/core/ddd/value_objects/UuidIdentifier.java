package com.nexus_flow.core.ddd.value_objects;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public abstract class UuidIdentifier implements Serializable {

    protected String value;

    public UuidIdentifier(String value) {

        ensureValidUuid(value);

        this.value = value;
    }

    protected UuidIdentifier() {
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
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        UuidIdentifier that = (UuidIdentifier) o;
        return value.equals(that.value);
    }

    private void ensureValidUuid(String value) {
        UUID.fromString(value);
    }
}