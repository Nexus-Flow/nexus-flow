package com.nexus_flow.core.criteria.infrastructure.elasticsearch;


import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.nexus_flow.core.criteria.domain.PagedCriteria;
import com.nexus_flow.core.criteria.domain.page.Pageable;
import com.nexus_flow.core.criteria.infrastructure.FilterConverter;
import com.nexus_flow.core.criteria.infrastructure.elasticsearch.value_objects.SourceAndFields;
import com.nexus_flow.core.ddd.annotations.NexusFlowService;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;

import java.util.stream.Collectors;

@NexusFlowService
@Log4j2
public class ElasticSearchRepository {

    private final ElasticSearchCriteriaConverter criteriaConverter;
    private final ElasticsearchClient            elasticsearchClient;

    public ElasticSearchRepository(ElasticSearchCriteriaConverter criteriaConverter,
                                   ElasticsearchClient elasticsearchClient) {
        this.criteriaConverter   = criteriaConverter;
        this.elasticsearchClient = elasticsearchClient;
    }

    @SneakyThrows
    public <T> Pageable<T> search(PagedCriteria pagedCriteria,
                                  SourceAndFields sourceAndFields,
                                  FilterConverter filterConverter,
                                  Class<T> targetClass) {

        SearchRequest searchRequest = criteriaConverter.toSearchRequest(pagedCriteria,
                                                                        sourceAndFields,
                                                                        filterConverter);

        SearchResponse<T> response = elasticsearchClient.search(searchRequest, targetClass);

        return new Pageable<>(response.hits().hits().stream()
                                      .map(Hit::source)
                                      .collect(Collectors.toList()),
                              response.hits().total().value(),
                              pagedCriteria.getPage());
    }


}

