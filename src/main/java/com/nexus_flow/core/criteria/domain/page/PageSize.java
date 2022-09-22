package com.nexus_flow.core.criteria.domain.page;

import com.nexus_flow.core.ddd.value_objects.IntValueObject;

public class PageSize extends IntValueObject {

    public PageSize(String value) {
        super(value);
    }

    public PageSize(Integer value) {
        super(value);
    }

}
