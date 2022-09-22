package com.nexus_flow.core.criteria.app;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.nexus_flow.core.criteria.application.query.AndCriteriaQuery;
import com.nexus_flow.core.criteria.application.query.CriteriaQuery;
import com.nexus_flow.core.criteria.application.query.CriterionQuery;
import lombok.Data;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@JsonTypeName("and")
@Data
public class AndCriteriaRequest<F extends CriterionRequest> extends CriteriaRequest<F> {


    public AndCriteriaRequest() {
    }

    public AndCriteriaRequest(List<F> filters) {
        super(filters);
    }

    @SafeVarargs
    public AndCriteriaRequest(F... filter) {
        super(filter);
    }

    @SafeVarargs
    public static <F extends CriterionRequest> AndCriteriaRequest<F> with(F... criteria) {
        return new AndCriteriaRequest<>(criteria);
    }

    public AndCriteriaRequest<F> and(List<? extends CriterionQuery> filter) {
        this.addCriteria((List<F>) filter);
        return this;
    }

    @SafeVarargs
    public final AndCriteriaRequest<F> and(F... filter) {
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
        AndCriteriaRequest<F> that = (AndCriteriaRequest<F>) o;
        return Objects.equals(filters, that.filters);
    }

    @Override
    public String toString() {
        return filters.stream()
                .map(F::toString)
                .collect(Collectors.joining("AND", "{", "}"));
    }


    public CriteriaQuery toCriteriaQuery() {
        return new AndCriteriaQuery(filters.stream()
                                            .map(CriterionRequest::toCriteriaQuery)
                                            .collect(Collectors.toList()));
    }

}


