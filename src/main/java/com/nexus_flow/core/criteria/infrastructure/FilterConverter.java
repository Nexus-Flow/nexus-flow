package com.nexus_flow.core.criteria.infrastructure;

import com.nexus_flow.core.criteria.domain.Module;
import com.nexus_flow.core.criteria.domain.filter.Filter;
import com.nexus_flow.core.criteria.domain.filter.FilterField;
import com.nexus_flow.core.criteria.domain.page.SortBy;

/**
 * This class converters the name of the field from the convention moduleName.fieldName to what is needed in each database, for instance
 * 'fieldName.value' in jpa with jsonb column or 'name.value.keyword' in ElasticSearch
 */
public interface FilterConverter {

    static String extractModuleName(FilterField field) {
        return extractModuleName(field.getValue());
    }

    static String extractModuleName(String field) {
        return field.split("[.]", 2)[0];
    }

    static String extractModuleName(Module module) {
        return extractModuleName(module.getValue());
    }

    static String extractFieldName(FilterField field) {
        return extractFieldName(field.getValue());
    }

    static String extractFieldName(String field) {
        return field.split("[.]", 2)[1];
    }

    Filter convert(Filter filter);

    SortBy convert(SortBy sortBy);


}
