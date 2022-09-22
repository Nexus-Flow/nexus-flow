package com.nexus_flow.core.criteria.domain;

import com.nexus_flow.core.criteria.application.query.*;
import com.nexus_flow.core.criteria.domain.filter.Filter;
import com.nexus_flow.core.criteria.domain.filter.FilterOperator;
import com.nexus_flow.core.criteria.domain.page.Page;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PagedCriteria {

    protected Page page;
    private   Criteria<? extends Criterion> criteria;
    private Module requestedModule;

    private PagedCriteria(Criteria<? extends Criterion> criteria) {
        this(new Page(), criteria);
    }

    private PagedCriteria(Page page, Criteria<? extends Criterion> criteria) {
        this.page     = page;
        this.criteria = criteria;
    }

    private PagedCriteria(Page page,
                          Criteria<? extends Criterion> criteria,
                          Module requestedModule) {
        this.page            = page;
        this.criteria        = criteria;
        this.requestedModule = requestedModule;
    }

    public static PagedCriteria with(Page page) {
        return new PagedCriteria(page, AndCriteria.with());
    }

    public static PagedCriteria with(Page page, Criteria<? extends Criterion> criterion) {
        return new PagedCriteria(page, criterion);
    }

    public static PagedCriteria with(Page page, Criteria<? extends Criterion> criterion, Module requestedModule) {
        return new PagedCriteria(page, criterion, requestedModule);
    }

    public static PagedCriteria and(Criterion... criterion) {
        return new PagedCriteria(AndCriteria.with(criterion));
    }

    public static PagedCriteria or(Criterion... criterion) {
        return new PagedCriteria(OrCriteria.with(criterion));
    }

    public static PagedCriteria fromQuery(PagedCriteriaQuery pagedCriteriaQuery) {
        return PagedCriteriaMapper.fromQuery(pagedCriteriaQuery);
    }

    public PagedCriteria existingAnd(Criterion... criterion) {
        this.criteria = AndCriteria.with(this.criteria).and(criterion);
        return this;
    }

    public PagedCriteria existingOr(Criterion... criterion) {
        this.criteria = OrCriteria.with(this.criteria).or(criterion);
        return this;
    }

    public PagedCriteria modifyPage(Page page) {
        this.page = page;
        return this;
    }

    public PagedCriteria requestedModule(Module module) {
        this.requestedModule = module;
        return this;
    }

    private List<Filter> extractFilters(List<? extends Criterion> criteriaList) {
        return criteriaList.stream()
                .flatMap(criterion -> criterion instanceof Filter ?
                        Stream.of((Filter) criterion) :
                        extractFilters(((Criteria<? extends Criterion>) criterion).getFilters()).stream())
                .collect(Collectors.toList());
    }

    public Criterion getCriteria() {
        return criteria;
    }

    public Page getPage() {
        return page;
    }

    protected void setPage(Page page) {
        this.page = page;
    }

    public Module getAggregateToRequest() {
        return requestedModule;
    }

    @Override
    public int hashCode() {
        return Objects.hash(page);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PagedCriteria criteriaHolder = (PagedCriteria) o;
        return Objects.equals(page, criteriaHolder.page);
    }

    public static class PagedCriteriaMapper {
        private PagedCriteriaMapper() {
        }

        public static PagedCriteria fromQuery(PagedCriteriaQuery pagedCriteriaQuery) {
            CriteriaQuery<? extends CriterionQuery> criteriaQuery = pagedCriteriaQuery.getCriteriaQuery();

            return new PagedCriteria(fromQuery(pagedCriteriaQuery.getPage()),
                    criteriaQuery.getFilters().isEmpty() ?
                            new AndCriteria<>() :
                            fromQuery(criteriaQuery));
        }

        private static Page fromQuery(PageQuery pageQuery) {
            return Page.fromQuery(pageQuery);
        }

        private static Criteria<? extends Criterion> fromQuery(CriteriaQuery<? extends CriterionQuery> criteriaQuery) {
            if (criteriaQuery instanceof AndCriteriaQuery) {
                return fromAndQuery(criteriaQuery.getFilters());
            } else {
                return fromOrQuery(criteriaQuery.getFilters());
            }
        }

        private static Criteria<? extends Criterion> fromAndQuery(List<CriterionQuery> filters) {
            AndCriteria<Criterion> andCriteria = new AndCriteria<>();
            for (CriterionQuery filter : filters) {
                if (filter instanceof OrCriteriaQuery) {
                    andCriteria.and(fromOrQuery(((OrCriteriaQuery<? extends CriterionQuery>) filter).getFilters()));
                } else if (filter instanceof AndCriteriaQuery) {
                    andCriteria.and(fromAndQuery(((AndCriteriaQuery<? extends CriterionQuery>) filter).getFilters()));
                } else {
                    andCriteria.and(fromFilterQuery((QueryFilter) filter));
                }
            }
            return andCriteria;
        }

        private static Criteria<? extends Criterion> fromOrQuery(List<CriterionQuery> filters) {
            OrCriteria<Criterion> orCriteria = new OrCriteria<>();
            for (CriterionQuery filter : filters) {
                if (filter instanceof OrCriteriaQuery) {
                    orCriteria.or(fromOrQuery(((OrCriteriaQuery<? extends CriterionQuery>) filter).getFilters()));
                } else if (filter instanceof AndCriteriaQuery) {
                    orCriteria.or(fromAndQuery(((AndCriteriaQuery<? extends CriterionQuery>) filter).getFilters()));
                } else {
                    orCriteria.or(fromFilterQuery((QueryFilter) filter));
                }
            }
            return orCriteria;
        }

        private static Criterion[] fromFilterQuery(QueryFilter filter) {
            boolean allowsNestedFilters = FilterOperator.fromValue(filter.getOperator().toUpperCase()).isAllowsNestedFilters();

            if (allowsNestedFilters) {
                return filter.getValue().stream()
                        .map(value -> Filter.create(filter.getField(), filter.getOperator(), filter.getFormat(), value))
                        .toArray(Criterion[]::new);

            }
            return new Criterion[]{Filter.fromQueryFilter(filter)};
        }

    }
}
