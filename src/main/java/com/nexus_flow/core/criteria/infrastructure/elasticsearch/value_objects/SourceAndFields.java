package com.nexus_flow.core.criteria.infrastructure.elasticsearch.value_objects;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SourceAndFields {

    private List<FieldToFetch> fieldsToFetch;
    private FetchSource        fetchSource;
    private List<SourceFilter> sourceFilters;

    public static SourceAndFields fetchingSourceAnd(List<FieldToFetch> fieldsToFetch) {
        return new SourceAndFields(fieldsToFetch, FetchSource.yes(), new ArrayList<>());
    }

    public static SourceAndFields noFetchingSource(List<FieldToFetch> fieldsToFetch) {
        return new SourceAndFields(fieldsToFetch, FetchSource.no(), new ArrayList<>());
    }

    public static SourceAndFields fetchingSource() {
        return new SourceAndFields(new ArrayList<>(), FetchSource.yes(), new ArrayList<>());
    }

    public static SourceAndFields noFetchingSource() {
        return new SourceAndFields(new ArrayList<>(), FetchSource.no(), new ArrayList<>());
    }

    public static SourceAndFields filteringSource(List<SourceFilter> sourceFilter) {
        return new SourceAndFields(new ArrayList<>(), FetchSource.yes(), sourceFilter);
    }

    public static SourceAndFields filteringSource(SourceFilter sourceFilter) {
        return new SourceAndFields(new ArrayList<>(), FetchSource.yes(), Collections.singletonList(sourceFilter));
    }

    public static SourceAndFields filteringSource(List<SourceFilter> sourceFilter, List<FieldToFetch> fieldsToFetch) {
        return new SourceAndFields(fieldsToFetch, FetchSource.yes(), sourceFilter);
    }

    public List<FieldToFetch> getFieldsToFetch() {
        return new ArrayList<>(fieldsToFetch);
    }

    public List<SourceFilter> getSourceFilters() {
        return new ArrayList<>(sourceFilters);
    }

}
