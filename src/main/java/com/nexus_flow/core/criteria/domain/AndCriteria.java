package com.nexus_flow.core.criteria.domain;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Searches what matches one filter AND another:  Filter AND Filter AND Filter <br> *
 */
public final class AndCriteria<F extends Criterion> extends Criteria<F> {

    public AndCriteria() {
    }

    private AndCriteria(List<F> filters) {
        super(filters);
    }

    @SafeVarargs
    private AndCriteria(F... filter) {
        super(filter);
    }

    @SafeVarargs
    public static <F extends Criterion> AndCriteria<F> with(F... criteria) {
        return new AndCriteria<>(criteria);
    }

    public AndCriteria<F> and(List<F> filter) {
        this.addCriteria(filter);
        return this;
    }

    @SafeVarargs
    public final AndCriteria<F> and(Criterion... filter) {
        this.addCriteria((F[]) filter);
        return this;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), criteriaList);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        AndCriteria<F> that = (AndCriteria<F>) o;
        return Objects.equals(criteriaList, that.criteriaList);
    }

    @Override
    public String toString() {
        return criteriaList.stream()
                .map(F::toString)
                .collect(Collectors.joining("AND", "{", "}"));
    }


}
