package com.nexus_flow.core.criteria.infrastructure.elasticsearch.value_objects;

import java.util.Objects;

public record FieldToFetch(String name) {

    public FieldToFetch(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FieldToFetch that = (FieldToFetch) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

}
