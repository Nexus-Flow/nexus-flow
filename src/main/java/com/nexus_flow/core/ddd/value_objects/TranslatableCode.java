package com.nexus_flow.core.ddd.value_objects;

import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.ddd.exceptions.WrongFormat;

import java.util.Objects;

public class TranslatableCode {

    protected String value;

    private TranslatableCode() {
    }

    public TranslatableCode(String value) {
        checkFormat(value);
        this.value = Utils.toSnake(value);
    }

    private void checkFormat(String value) {
        if (value == null || value.isBlank()) {
            throw new WrongFormat("Translation code can't be null nor empty");
        }
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
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TranslatableCode that = (TranslatableCode) o;
        return Objects.equals(value, that.value);
    }
}
