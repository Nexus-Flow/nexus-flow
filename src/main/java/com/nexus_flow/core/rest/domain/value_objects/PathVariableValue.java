package com.nexus_flow.core.rest.domain.value_objects;

import com.nexus_flow.core.ddd.value_objects.StringValueObject;

public class PathVariableValue extends StringValueObject {

    public PathVariableValue(String value) {
        super(value);
        checkNotNull();
    }
}
