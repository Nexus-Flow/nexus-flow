package com.nexus_flow.core.criteria.domain;

import com.nexus_flow.core.ddd.value_objects.StringValueObject;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public class Module extends StringValueObject {

    public Module(String value) {
        super(value.toLowerCase());
    }
}
