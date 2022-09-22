package com.nexus_flow.core.criteria.app;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.nexus_flow.core.criteria.application.query.QueryFilter;
import com.nexus_flow.core.criteria.domain.filter.FieldType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@JsonTypeName("filter")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FilterRequest implements CriterionRequest, Serializable {

    private String       field;
    private String       operator;
    private String       format;
    private List<String> value;


    public FilterRequest(String field, String operator, String format, String... value) {
        this.field    = field;
        this.operator = operator;
        this.format   = format;
        this.value    = List.of(value);
    }

    public static FilterRequest forField(String field) {
        FilterRequest queryFilter = new FilterRequest();
        queryFilter.setField(field);
        queryFilter.setFormat(FieldType.STRING.name());
        return queryFilter;
    }

    public FilterRequest operation(String operator) {
        this.operator = operator;
        return this;
    }

    public FilterRequest format(String format) {
        this.format = FieldType.valueOfIgnoreCase(format).name();
        return this;
    }

    public FilterRequest value(List<String> values) {
        this.value = new ArrayList<>(values);
        return this;
    }

    public FilterRequest value(String... values) {
        this.value = List.of(values);
        return this;
    }


    @Override
    public QueryFilter toCriteriaQuery() {
        return new QueryFilter(field, operator, format, value);
    }

}
