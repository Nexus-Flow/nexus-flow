package com.nexus_flow.core.criteria.infrastructure.elasticsearch;


import co.elastic.clients.elasticsearch._types.FieldSort;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldAndFormat;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.SourceConfig;
import com.nexus_flow.core.configurations.elasticsearch.ElasticSearchProperties;
import com.nexus_flow.core.criteria.domain.Module;
import com.nexus_flow.core.criteria.domain.*;
import com.nexus_flow.core.criteria.domain.filter.Filter;
import com.nexus_flow.core.criteria.domain.page.Page;
import com.nexus_flow.core.criteria.domain.page.PageNumber;
import com.nexus_flow.core.criteria.domain.page.PageSize;
import com.nexus_flow.core.criteria.domain.page.Sort;
import com.nexus_flow.core.criteria.infrastructure.FilterConverter;
import com.nexus_flow.core.criteria.infrastructure.elasticsearch.value_objects.SourceAndFields;
import com.nexus_flow.core.criteria.infrastructure.elasticsearch.value_objects.SourceFilter;
import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.ddd.annotations.NexusFlowService;
import com.nexus_flow.core.ddd.exceptions.WrongFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@NexusFlowService
public final class ElasticSearchCriteriaConverter {

    private final FilterToElasticSearchQueryMapper toQueryMapper;
    private final ElasticSearchProperties          elasticSearchProperties;

    public ElasticSearchCriteriaConverter(FilterToElasticSearchQueryMapper toQueryMapper,
                                          ElasticSearchProperties elasticSearchProperties) {
        this.toQueryMapper           = toQueryMapper;
        this.elasticSearchProperties = elasticSearchProperties;
    }


    public SearchRequest toSearchRequest(PagedCriteria pagedCriteria,
                                         SourceAndFields sourceAndFields,
                                         FilterConverter filterConverter) {

        Module requestedModule = getModule(pagedCriteria);

        BoolQuery boolQuery = toBoolQuery(pagedCriteria.getCriteria(), filterConverter);

        List<FieldAndFormat> fieldAndFormats = sourceAndFields.getFieldsToFetch().stream().map(field -> FieldAndFormat.of(builder -> builder.field(field.name()))).collect(Collectors.toList());

        SourceConfig sourceConfig;
        if (!sourceAndFields.getSourceFilters().isEmpty()) {
            sourceConfig = new SourceConfig.Builder().filter(builder1 -> builder1.includes(sourceAndFields.getSourceFilters().stream().map(SourceFilter::name).collect(Collectors.toList()))).build();
        } else {
            sourceConfig = new SourceConfig.Builder().fetch(sourceAndFields.getFetchSource().getValue()).build();
        }

        return SearchRequest.of(builder -> builder.index(requestedModule.getValue()).source(sourceConfig).fields(fieldAndFormats).from(pageNumberToFrom(pagedCriteria)).size(extractPageSize(pagedCriteria)).sort(buildSortOptionsList(pagedCriteria, filterConverter)).query(builder1 -> builder1.bool(boolQuery)));
    }

    private Module getModule(PagedCriteria pagedCriteria) {
        Optional<Module> optionalModule = Optional.ofNullable(pagedCriteria.getAggregateToRequest());
        if (optionalModule.isEmpty()) {
            throw new WrongFormat("Requested Module can't be null");
        }
        return optionalModule.get();
    }

    private BoolQuery toBoolQuery(Criterion criterion, FilterConverter filterConverter) {
        BoolQuery boolQuery;
        if (criterion instanceof AndCriteria) {
            boolQuery = toAndBoolQuery((AndCriteria<? extends Criterion>) criterion, filterConverter);
        } else if (criterion instanceof OrCriteria) {
            boolQuery = toOrBoolQuery((OrCriteria<? extends Criterion>) criterion, filterConverter);
        } else {
            throw new IllegalArgumentException("Criteria type not exist.");
        }
        return boolQuery;
    }

    private BoolQuery toAndBoolQuery(AndCriteria<? extends Criterion> andCriteria, FilterConverter filterConverter) {
        List<Query> mustQueries    = new ArrayList<>();
        List<Query> mustNotQueries = new ArrayList<>();
        List<Query> shouldQueries  = new ArrayList<>();
        andCriteria.getFilters().forEach(innerCriteria -> {
            if (innerCriteria instanceof Filter) {
                Query filter = this.toQueryMapper.toQuery((Filter) innerCriteria, filterConverter);
                if (!filter.isBool()) mustQueries.add(filter);
                else {
                    mustQueries.addAll(((BoolQuery) filter._get()).must());
                    mustNotQueries.addAll(((BoolQuery) filter._get()).mustNot());
                    shouldQueries.addAll(((BoolQuery) filter._get()).should());
                }
            } else {
                mustQueries.add(toBoolQuery(innerCriteria, filterConverter)._toQuery());
            }
        });
        return BoolQuery.of(builder -> builder.must(mustQueries).mustNot(mustNotQueries).should(shouldQueries));
    }

    private BoolQuery toOrBoolQuery(OrCriteria<? extends Criterion> orCriteria, FilterConverter filterConverter) {
        List<Query> shouldQueries = new ArrayList<>();
        orCriteria.getFilters().forEach(innerCriteria -> {
            if (innerCriteria instanceof Filter) {
                Query filter = this.toQueryMapper.toQuery((Filter) innerCriteria, filterConverter);
                shouldQueries.add(filter);
            } else {
                shouldQueries.add(toBoolQuery(innerCriteria, filterConverter)._toQuery());
            }
        });
        return BoolQuery.of(builder -> builder.should(shouldQueries));
    }

    private Integer pageNumberToFrom(PagedCriteria pagedCriteria) {
        return extractPageNumber(pagedCriteria.getPage()) * extractPageSize(pagedCriteria);
    }

    private Integer extractPageNumber(Page page) {
        return Optional.ofNullable(page.getNumber()).map(PageNumber::getValue).orElse(elasticSearchProperties.getPageDefault().getNumber());
    }

    private Integer extractPageSize(PagedCriteria pagedCriteria) {
        return extractPageSize(pagedCriteria.getPage());
    }

    private Integer extractPageSize(Page page) {
        return Optional.ofNullable(page.getSize()).map(PageSize::getValue).orElse(elasticSearchProperties.getPageDefault().getSize());
    }

    private List<SortOptions> buildSortOptionsList(PagedCriteria criteria, FilterConverter filterConverter) {
        List<SortOptions> sortOptions = criteria.getPage().getSort().getOrderList().stream().map(order -> buildSortOptions(order, filterConverter)).collect(Collectors.toList());
        return sortOptions.isEmpty() ? defaultSortOptions() : sortOptions;
    }

    private SortOptions buildSortOptions(Sort.Order order, FilterConverter filterConverter) {
        return new SortOptions.Builder().field(new FieldSort.Builder().field(filterConverter.convert(order.getField()).getValue()).order(SortOrder.valueOf(Utils.toCamel(order.getDirection().value()))).build()).build();
    }

    private List<SortOptions> defaultSortOptions() {
        return List.of(new SortOptions.Builder().field(new FieldSort.Builder().field(elasticSearchProperties.getPageDefault().getSortBy()).order(SortOrder.valueOf(Utils.toCamel(elasticSearchProperties.getPageDefault().getSortDirection().toUpperCase()))).build()).build());
    }


}

