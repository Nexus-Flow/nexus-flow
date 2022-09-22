package com.nexus_flow.core.criteria.domain;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Searches what matches one filter or another:  Filter OR Filter OR Filter<br>
 * It can be compound by list of AndCriteria, which in the end is: (Filter AND Filter) OR (Filter AND Filter)
 */
public final class OrCriteria<F extends Criterion> extends Criteria<F> {

    public OrCriteria() {
        super();
    }

    public OrCriteria(List<F> filters) {
        super(filters);
    }

    @SafeVarargs
    public OrCriteria(F... filters) {
        super(filters);
    }

    @SafeVarargs
    public static <F extends Criterion> OrCriteria<F> with(F... criteria) {
        return new OrCriteria<>(criteria);
    }

    public OrCriteria<F> or(List<F> filter) {
        this.addCriteria(filter);
        return this;
    }

    public final OrCriteria<F> or(Criterion... filter) {
        this.addCriteria((F[]) filter);
        return this;
    }


    @Override
    public String toString() {
        return criteriaList.stream()
                .map(F::toString)
                .collect(Collectors.joining("OR", "{", "}"));
    }
}
