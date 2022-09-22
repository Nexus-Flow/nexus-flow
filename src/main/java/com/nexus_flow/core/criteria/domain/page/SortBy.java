package com.nexus_flow.core.criteria.domain.page;

import com.nexus_flow.core.ddd.value_objects.StringValueObject;

public final class SortBy extends StringValueObject {

    private SortBy() {
        super();
    }

    public SortBy(String value) {
        super(value);
    }

    public static SortBy unsorted() {
        return new SortBy();
    }


}
