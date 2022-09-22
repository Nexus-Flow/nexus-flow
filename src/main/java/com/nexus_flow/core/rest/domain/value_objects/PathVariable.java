package com.nexus_flow.core.rest.domain.value_objects;

import com.nexus_flow.core.ddd.exceptions.WrongFormat;

import java.util.Map;
import java.util.Objects;

public class PathVariable {

    private PathVariableName  name;
    private PathVariableValue value;

    public PathVariable(PathVariableName name, PathVariableValue value) {
        this.name  = name;
        this.value = value;
    }

    public PathVariable(Map<String, Object> primitives) {
        checkPrimitivesMap(primitives);
        this.name  = new PathVariableName((String) primitives.get("name"));
        this.value = new PathVariableValue((String) primitives.get("value"));
    }

    private void checkPrimitivesMap(Map<String, Object> map) {
        if (map == null) {
            throw new WrongFormat("Map of primitives to create PathVariable can't be null");
        }
    }

    public PathVariableName getName() {
        return name;
    }

    public PathVariableValue getValue() {
        return value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PathVariable that = (PathVariable) o;
        return Objects.equals(name, that.name) &&
                Objects.equals(value, that.value);
    }
}
