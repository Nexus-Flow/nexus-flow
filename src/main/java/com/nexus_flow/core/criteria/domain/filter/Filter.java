package com.nexus_flow.core.criteria.domain.filter;

import com.nexus_flow.core.criteria.application.query.QueryFilter;
import com.nexus_flow.core.criteria.domain.Criterion;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class Filter implements Criterion {

    private final FilterField    field;
    private final FilterOperator operator;
    private final FieldType      fieldType;
    private final FilterValue    value;


    private Filter(FilterField field,
                   FilterOperator operator,
                   FieldType fieldType,
                   FilterValue values) {
        this.field     = field;
        this.operator  = operator;
        this.fieldType = fieldType == null ? FieldType.STRING : fieldType;
        this.value     = values;
    }

    public static Filter create(FilterField field, FilterOperator operator, FilterValue value) {
        return new Filter(field, operator, FieldType.STRING, value);
    }

    public static Filter create(String field, String operator, String fieldType, String... value) {
        FieldType finalFieldType = fieldType == null ? FieldType.STRING : FieldType.valueOf(fieldType.toUpperCase());
        return new Filter(
                new FilterField(field),
                FilterOperator.fromValue(operator.toUpperCase()),
                finalFieldType,
                new FilterValue(Arrays.stream(value)
                                        .map(s -> finalFieldType.getConstructor().apply(s))
                                        .collect(Collectors.toList()))
        );
    }


    public static Filter fromQueryFilter(QueryFilter queryFilter) {
        return new Filter(
                new FilterField(queryFilter.getField()),
                FilterOperator.fromValue(queryFilter.getOperator().toUpperCase()),
                null,
                new FilterValue(queryFilter.getValue())
        );
    }

    public static Filter fromValues(Map<String, String> values) {
        return new Filter(
                new FilterField(values.get("field")),
                FilterOperator.fromValue(values.get("operator")),
                null,
                new FilterValue(List.of(values.get("value")))
        );
    }

    public FilterField getField() {
        return field;
    }

    public FilterOperator getOperator() {
        return operator;
    }

    public FilterValue getValue() {
        return value;
    }

    public FieldType getFieldType() {
        return fieldType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, operator, value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Filter filter = (Filter) o;
        return Objects.equals(field, filter.field) &&
                operator == filter.operator &&
                Objects.equals(value, filter.value);
    }

    @Override
    public String toString() {
        return " " + field +
                " " + operator.getOperator() +
                " " + value.getValues() +
                " ";
    }
}
