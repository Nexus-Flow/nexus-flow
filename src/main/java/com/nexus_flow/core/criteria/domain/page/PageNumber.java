package com.nexus_flow.core.criteria.domain.page;


import com.nexus_flow.core.ddd.value_objects.IntValueObject;

public class PageNumber extends IntValueObject {

    public PageNumber(String value) {
        super(value);
    }

    public PageNumber(Integer value) {
        super(value);
    }

}
