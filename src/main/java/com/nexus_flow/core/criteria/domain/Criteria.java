package com.nexus_flow.core.criteria.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class Criteria<F extends Criterion> implements Criterion {

    protected final List<F> criteriaList = new ArrayList<>();

    protected Criteria() {
    }

    protected Criteria(List<F> criteriaList) {
        this.criteriaList.addAll(criteriaList);
    }

    @SafeVarargs
    protected Criteria(F... criteria) {
        this.criteriaList.addAll(Arrays.asList(criteria));
    }

    public final Criteria<F> addCriteria(List<F> criteria) {
        this.criteriaList.addAll(criteria);
        return this;
    }

    @SafeVarargs
    public final Criteria<F> addCriteria(F... criteria) {
        this.criteriaList.addAll(Arrays.asList(criteria));
        return this;
    }


    public final Criteria<F> empty() {
        criteriaList.clear();
        return this;
    }

    public List<F> getFilters() {
        return criteriaList;
    }

}