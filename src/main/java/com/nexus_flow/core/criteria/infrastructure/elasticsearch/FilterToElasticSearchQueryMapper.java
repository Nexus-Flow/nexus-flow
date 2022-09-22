package com.nexus_flow.core.criteria.infrastructure.elasticsearch;


import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.NestedQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermsQueryField;
import co.elastic.clients.util.ObjectBuilder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus_flow.core.criteria.domain.filter.Filter;
import com.nexus_flow.core.criteria.domain.filter.FilterField;
import com.nexus_flow.core.criteria.domain.filter.FilterOperator;
import com.nexus_flow.core.criteria.infrastructure.FilterConverter;
import com.nexus_flow.core.ddd.annotations.NexusFlowService;
import lombok.SneakyThrows;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@NexusFlowService
public final class FilterToElasticSearchQueryMapper {

    private final Map<FilterOperator, Function<Filter, Function<Query.Builder, ObjectBuilder<Query>>>> elasticSearchQueriesMap
            = new EnumMap<>(FilterOperator.class);

    public FilterToElasticSearchQueryMapper() {
        fillElasticSearchQueriesMap();
    }


    private void fillElasticSearchQueriesMap() {
        elasticSearchQueriesMap.put(FilterOperator.EQUAL, this::equalQuery);
        elasticSearchQueriesMap.put(FilterOperator.LIKE, this::likeQuery);
        elasticSearchQueriesMap.put(FilterOperator.NOT_EQUAL, this::notEqualQuery);
        elasticSearchQueriesMap.put(FilterOperator.CONTAINS, this::containsNoCaseSensitiveQuery);
        elasticSearchQueriesMap.put(FilterOperator.NOT_CONTAINS, this::notContainsQuery);
        elasticSearchQueriesMap.put(FilterOperator.IN, this::inQuery);
        elasticSearchQueriesMap.put(FilterOperator.CONTAINS_ALL_OBJECTS, this::containsAllObjectQuery);
        elasticSearchQueriesMap.put(FilterOperator.NOT_CONTAINS_ANY_OBJECTS, this::notContainsAnyObjectQuery);
        elasticSearchQueriesMap.put(FilterOperator.CONTAINS_ANY_OBJECTS, this::containsAnyObjectQuery);
        elasticSearchQueriesMap.put(FilterOperator.EXISTS_FIELD, this::existsFieldObjectQuery);
    }

    @SneakyThrows
    private Function<Query.Builder, ObjectBuilder<Query>> notContainsAnyObjectQuery(Filter filter) {
        List<Query> functionList = convertObjectsToAListOfNestedQueries(filter);
        return builder -> builder.bool(builder1 -> builder1
                .mustNot(functionList));
    }

    @SneakyThrows
    private Function<Query.Builder, ObjectBuilder<Query>> containsAllObjectQuery(Filter filter) {
        List<Query> functionList = convertObjectsToAListOfNestedQueries(filter);
        return builder -> builder.bool(builder1 -> builder1
                .must(functionList));
    }

    @SneakyThrows
    private Function<Query.Builder, ObjectBuilder<Query>> containsAnyObjectQuery(Filter filter) {
        List<Query> functionList = convertObjectsToAListOfNestedQueries(filter);
        return builder -> builder.bool(builder1 -> builder1
                .should(functionList));
    }

    @SneakyThrows
    private Function<Query.Builder, ObjectBuilder<Query>> existsFieldObjectQuery(Filter filter) {
        return builder -> builder.exists(builder1 -> builder1
                .field(filter.getValue().getFirst().toString()));
    }

    private List<Query> convertObjectsToAListOfNestedQueries(Filter filter) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        JavaType objectType = mapper.getTypeFactory().constructMapType(Map.class,
                                                                       String.class,
                                                                       String.class);
        JavaType objectListType = mapper.getTypeFactory().constructCollectionLikeType(List.class,
                                                                                      objectType);
        List<Map<String, String>> listOfObjects = new ObjectMapper().readValue((String) filter.getValue().getFirst(),
                                                                               objectListType);

        return listOfObjects.stream()
                .map(objectAsMap -> convertAnObjectToNestedQuery(objectAsMap, filter.getField()))
                .collect(Collectors.toList());
    }

    private Query convertAnObjectToNestedQuery(Map<String, String> objectAsMap, FilterField field) {
        List<Query> objectQueries = objectAsMap.keySet().stream()
                .map(objectField -> new Query.Builder().term(builder -> builder
                                .field(objectField)
                                .value(builder1 -> builder1.stringValue(objectAsMap.get(objectField))))
                        .build())
                .collect(Collectors.toList());
        return new Query.Builder()
                .nested(new NestedQuery.Builder()
                                .path(field.getValue())
                                .query(builder2 -> builder2.bool(builder3 -> builder3
                                        .must(objectQueries)))
                                .build())
                .build();
    }

    private Function<Query.Builder, ObjectBuilder<Query>> equalQuery(Filter filter) {
        return builder1 -> builder1
                .term(builder2 -> builder2
                        .field(filter.getField().getValue())
                        .value(builder3 -> builder3.stringValue((String) filter.getValue().getFirst())));
    }

    private Function<Query.Builder, ObjectBuilder<Query>> likeQuery(Filter filter) {
        return builder1 -> builder1
                .queryString(builder2 -> builder2
                        .query((String) filter.getValue().getFirst())
                        .defaultField(filter.getField().getValue()));
    }

    private Function<Query.Builder, ObjectBuilder<Query>> notEqualQuery(Filter filter) {
        return builder1 -> builder1
                .bool(builder2 -> builder2
                        .mustNot(builder3 -> builder3
                                .term(builder4 -> builder4
                                        .field(filter.getField().getValue())
                                        .value(builder5 -> builder5.stringValue(
                                                (String) filter.getValue().getFirst())))));
    }

    private Function<Query.Builder, ObjectBuilder<Query>> containsNoCaseSensitiveQuery(Filter filter) {
        return builder1 -> builder1
                .matchPhrasePrefix(builder2 -> builder2
                        .field(filter.getField().getValue())
                        .query(((String) filter.getValue().getFirst()).toLowerCase()));
    }

    private Function<Query.Builder, ObjectBuilder<Query>> notContainsQuery(Filter filter) {
        return builder1 -> builder1
                .bool(builder2 -> builder2
                        .mustNot(builder3 -> builder3
                                .match(builder4 -> builder4
                                        .field(filter.getField().getValue())
                                        .query(builder5 -> builder5.stringValue((String) filter.getValue()
                                                .getFirst())))));
    }


    @SuppressWarnings("unchecked")
    private Function<Query.Builder, ObjectBuilder<Query>> inQuery(Filter filter) {
        return builder1 -> builder1
                .terms(builder2 -> builder2
                        .field(filter.getField().getValue())
                        .terms(TermsQueryField.of(builder -> builder
                                .value((List<FieldValue>) filter.getValue().getValues().stream()
                                        .map(value -> FieldValue.of((String) value))
                                        .collect(Collectors.toList())))));

    }

    public Query toQuery(Filter filter, FilterConverter filterConverter) {
        Function<Filter, Function<Query.Builder, ObjectBuilder<Query>>> transformer = elasticSearchQueriesMap.get(filter.getOperator());
        if (transformer == null) {
            throw new IllegalArgumentException(String.format("Operation %s is not implemented yet for ElasticSearch",
                                                             filter.getOperator().name()));
        }
        return Query.of(transformer.apply(filterConverter.convert(filter)));
    }


}

