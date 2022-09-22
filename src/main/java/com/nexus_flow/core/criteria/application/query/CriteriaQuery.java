package com.nexus_flow.core.criteria.application.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class CriteriaQuery<F extends CriterionQuery> implements CriterionQuery {

    protected List<F> filters = new ArrayList<>();

    protected CriteriaQuery() {
    }

    protected CriteriaQuery(List<F> criteriaList) {
        this.filters.addAll(criteriaList);
    }

    @SafeVarargs
    protected CriteriaQuery(F... criteria) {
        this.filters.addAll(Arrays.asList(criteria));
    }

    public final CriteriaQuery<F> addCriteria(List<F> criteria) {
        this.filters.addAll(criteria);
        return this;
    }

    @SafeVarargs
    public final CriteriaQuery<F> addCriteria(F... criteria) {
        this.filters.addAll(Arrays.asList(criteria));
        return this;
    }


    public final CriteriaQuery<F> empty() {
        filters.clear();
        return this;
    }

    public <F extends CriterionQuery> List<F> getFilters() {
        return (List<F>) filters;
    }

}