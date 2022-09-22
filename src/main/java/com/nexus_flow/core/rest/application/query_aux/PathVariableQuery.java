package com.nexus_flow.core.rest.application.query_aux;

import com.nexus_flow.core.ddd.Utils;

import java.util.Map;
import java.util.Objects;

public final class PathVariableQuery {

    private String name;

    private String value;

    private PathVariableQuery() {
    }

    public PathVariableQuery(String name, String value) {
        this.name  = name;
        this.value = value;
    }

    private PathVariableQuery(PathVariableBuilder pathVariableBuilder) {
        name  = pathVariableBuilder.name;
        value = pathVariableBuilder.value;
    }

    public Map<String, Object> toMap() {
        return Utils.toMap(this);
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PathVariableQuery query = (PathVariableQuery) o;
        return Objects.equals(name, query.name) &&
                Objects.equals(value, query.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value);
    }

    public static final class PathVariableBuilder {
        private String name;
        private String value;

        public PathVariableBuilder() {
        }

        public PathVariableBuilder withName(String name) {
            this.name = name;
            return this;
        }

        public PathVariableBuilder withValue(String value) {
            this.value = value;
            return this;
        }

        public PathVariableQuery build() {
            return new PathVariableQuery(this);
        }
    }
}
