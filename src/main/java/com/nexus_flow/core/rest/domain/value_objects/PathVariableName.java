package com.nexus_flow.core.rest.domain.value_objects;

import com.nexus_flow.core.ddd.value_objects.StringValueObject;

public class PathVariableName extends StringValueObject {

    public PathVariableName(String value) {
        super(value);
        checkNotNull();
    }
}
