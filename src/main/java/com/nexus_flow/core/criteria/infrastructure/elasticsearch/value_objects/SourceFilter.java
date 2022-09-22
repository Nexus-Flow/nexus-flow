package com.nexus_flow.core.criteria.infrastructure.elasticsearch.value_objects;

import java.util.Objects;

public record SourceFilter(String name) {

    public SourceFilter(String name) {
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
        SourceFilter that = (SourceFilter) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

}
