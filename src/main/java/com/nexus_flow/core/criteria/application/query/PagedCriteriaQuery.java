package com.nexus_flow.core.criteria.application.query;

import com.nexus_flow.core.criteria.app.CriteriaRequest;
import com.nexus_flow.core.criteria.app.CriterionRequest;
import com.nexus_flow.core.criteria.app.PageRequest;
import lombok.Data;

import java.util.List;

@Data
public class PagedCriteriaQuery {

    protected PageQuery page;
    CriteriaQuery<? extends CriterionQuery> criteriaQuery;

    private PagedCriteriaQuery() {
        this.page     = new PageQuery();
        criteriaQuery = AndCriteriaQuery.with();
    }

    private PagedCriteriaQuery(CriteriaQuery<? extends CriterionQuery> criteria) {
        this.page     = new PageQuery();
        criteriaQuery = criteria;
    }

    private PagedCriteriaQuery(PageQuery page, CriteriaQuery<? extends CriterionQuery> criteria) {
        this.page     = page;
        criteriaQuery = criteria;
    }


    public static PagedCriteriaQuery with(PageQuery page, CriteriaQuery<? extends CriterionQuery> criteria) {
        return new PagedCriteriaQuery(page, criteria);

    }

    public static PagedCriteriaQuery and(PageQuery page, CriterionQuery... criterion) {
        return new PagedCriteriaQuery(page, AndCriteriaQuery.with(criterion));

    }

    public static PagedCriteriaQuery or(PageQuery page, CriterionQuery... criterion) {
        return new PagedCriteriaQuery(page, OrCriteriaQuery.with(criterion));
    }

    public static PagedCriteriaQuery and(CriterionQuery... criterion) {
        return new PagedCriteriaQuery(AndCriteriaQuery.with(criterion));
    }

    public static PagedCriteriaQuery or(CriterionQuery... criterion) {
        return new PagedCriteriaQuery(OrCriteriaQuery.with(criterion));
    }

    public static PagedCriteriaQuery empty() {
        return new PagedCriteriaQuery();
    }

    public static PagedCriteriaQuery fromRequest(CriteriaRequest<? extends CriterionRequest> criteriaRequest) {
        return new PagedCriteriaQuery(criteriaRequest.toCriteriaQuery());
    }

    public static PagedCriteriaQuery fromRequest(PageRequest pageRequest,
                                                 CriteriaRequest<? extends CriterionRequest> criteriaRequest) {
        return new PagedCriteriaQuery(PageQuery.fromRequest(pageRequest),
                                      criteriaRequest.toCriteriaQuery());
    }

    public PagedCriteriaQuery with(CriteriaQuery<? extends CriterionQuery> criteria) {
        criteriaQuery = criteria;
        return this;
    }

    public PagedCriteriaQuery allBackAnd(CriterionQuery... criterion) {
        criteriaQuery = AndCriteriaQuery.with(criteriaQuery).and(List.of(criterion));
        return this;
    }

    public PagedCriteriaQuery allBackOr(CriterionQuery... criterion) {
        criteriaQuery = OrCriteriaQuery.with(criteriaQuery).or(List.of(criterion));
        return this;
    }


    public PageQuery getPage() {
        return page;
    }

}
