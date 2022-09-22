package com.nexus_flow.core.criteria.infrastructure.elasticsearch.value_objects;

import com.nexus_flow.core.ddd.value_objects.BooleanValueObject;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FetchSource extends BooleanValueObject {

    public FetchSource(boolean value) {
        super(value);
    }

    public static FetchSource yes() {
        return new FetchSource(true);
    }

    public static FetchSource no() {
        return new FetchSource(false);
    }
}
