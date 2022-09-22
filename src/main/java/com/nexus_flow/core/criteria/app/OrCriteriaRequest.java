package com.nexus_flow.core.criteria.app;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.nexus_flow.core.criteria.application.query.CriteriaQuery;
import com.nexus_flow.core.criteria.application.query.OrCriteriaQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.stream.Collectors;

@JsonTypeName("or")
@Data
@EqualsAndHashCode(callSuper = true)
public class OrCriteriaRequest<F extends CriterionRequest> extends CriteriaRequest<F> {

    public OrCriteriaRequest() {
        super();
    }

    public OrCriteriaRequest(List<F> filters) {
        super(filters);
    }

    @SafeVarargs
    public OrCriteriaRequest(F... filters) {
        super(filters);
    }

    @SafeVarargs
    public static <F extends CriterionRequest> OrCriteriaRequest<F> with(F... criteria) {
        return new OrCriteriaRequest<>(criteria);
    }

    public OrCriteriaRequest<F> or(List<? extends CriterionRequest> filter) {
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
    public final OrCriteriaRequest<F> or(F... filter) {
        this.addCriteria(filter);
        return this;
    }


    @Override
    public String toString() {
        return filters.stream()
                .map(F::toString)
                .collect(Collectors.joining("OR", "{", "}"));
    }

    public CriteriaQuery toCriteriaQuery() {
        return new OrCriteriaQuery(filters.stream()
                                           .map(CriterionRequest::toCriteriaQuery)
                                           .collect(Collectors.toList()));
    }
}


