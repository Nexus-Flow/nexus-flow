package com.nexus_flow.core.criteria.application.query;

import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.Data;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@JsonTypeName("and")
@Data
public class AndCriteriaQuery<F extends CriterionQuery> extends CriteriaQuery<F> {


    public AndCriteriaQuery() {
    }

    public AndCriteriaQuery(List<F> filters) {
        super(filters);
    }

    @SafeVarargs
    public AndCriteriaQuery(F... filter) {
        super(filter);
    }

    @SafeVarargs
    public static <F extends CriterionQuery> AndCriteriaQuery<F> with(F... criteria) {
        return new AndCriteriaQuery<>(criteria);
    }

    public AndCriteriaQuery<F> and(List<? extends CriterionQuery> filter) {
        this.addCriteria((List<F>) filter);
        return this;
    }

    @SafeVarargs
    public final AndCriteriaQuery<F> and(F... filter) {
        this.addCriteria(filter);
        return this;
    }

    public List<F> getAnd() {
        return getFilters();
    }

    public void setAnd(List<F> filters) {
        this.filters = filters;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), filters);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        AndCriteriaQuery<F> that = (AndCriteriaQuery<F>) o;
        return Objects.equals(filters, that.filters);
    }

    @Override
    public String toString() {
        return filters.stream()
                .map(F::toString)
                .collect(Collectors.joining("AND", "{", "}"));
    }

}


