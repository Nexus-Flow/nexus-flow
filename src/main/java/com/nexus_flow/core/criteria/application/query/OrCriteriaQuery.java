package com.nexus_flow.core.criteria.application.query;

import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.stream.Collectors;

@JsonTypeName("or")
@Data
@EqualsAndHashCode(callSuper = true)
public class OrCriteriaQuery<F extends CriterionQuery> extends CriteriaQuery<F> {

    public OrCriteriaQuery() {
        super();
    }

    public OrCriteriaQuery(List<F> filters) {
        super(filters);
    }

    @SafeVarargs
    public OrCriteriaQuery(F... filters) {
        super(filters);
    }

    @SafeVarargs
    public static <F extends CriterionQuery> OrCriteriaQuery<F> with(F... criteria) {
        return new OrCriteriaQuery<>(criteria);
    }

    public OrCriteriaQuery<F> or(List<? extends CriterionQuery> filter) {
        this.addCriteria((List<F>) filter);
        return this;
    }


    public List<F> getOr() {
        return getFilters();
    }

    public void setOr(List<F> filters) {
        this.filters = filters;
    }


    @SafeVarargs
    public final OrCriteriaQuery<F> or(F... filter) {
        this.addCriteria(filter);
        return this;
    }


    @Override
    public String toString() {
        return filters.stream()
                .map(F::toString)
                .collect(Collectors.joining("OR", "{", "}"));
    }
}


