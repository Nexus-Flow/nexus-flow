package com.nexus_flow.core.criteria.application.query;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.nexus_flow.core.criteria.domain.filter.FieldType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@JsonTypeName("filter")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryFilter implements CriterionQuery {

    String       field;
    String       operator;
    String       format;
    List<String> value;


    public QueryFilter(String field, String operator, String format, String... value) {
        this.field    = field;
        this.operator = operator;
        this.format   = format;
        this.value    = List.of(value);
    }

    public static QueryFilter forField(String field) {
        QueryFilter queryFilter = new QueryFilter();
        queryFilter.setField(field);
        queryFilter.setFormat(FieldType.STRING.name());
        return queryFilter;
    }

    public QueryFilter operation(String operator) {
        this.operator = operator;
        return this;
    }

    public QueryFilter format(String format) {
        this.format = FieldType.valueOfIgnoreCase(format).name();
        return this;
    }

    public QueryFilter value(List<String> values) {
        this.value = new ArrayList<>(values);
        return this;
    }

    public QueryFilter value(String... values) {
        this.value = List.of(values);
        return this;
    }


}
