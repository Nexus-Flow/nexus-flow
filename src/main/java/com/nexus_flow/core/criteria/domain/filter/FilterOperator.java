package com.nexus_flow.core.criteria.domain.filter;

import com.nexus_flow.core.ddd.exceptions.WrongFormat;

import java.util.Arrays;

public enum FilterOperator {
    EQUAL("=", true),
    NOT_EQUAL("!=", true),
    GT(">", true),
    GT_OR_EQUALS(">=", true),
    LT("<", true),
    LT_OR_EQUALS("<=", true),
    CONTAINS("CONTAINS", true),
    LIKE("LIKE", true),
    NOT_CONTAINS("NOT_CONTAINS", true),
    IS_NULL("IS_NULL", false),
    IS_NOT_NULL("IS_NOT_NULL", false),
    IN("IN", false),
    NOT_IN("NOT_IN", false),
    BETWEEN("BETWEEN", false),
    CONTAINS_ALL_OBJECTS("CONTAINS_(All)_OBJECT(S)", false), // Contains the object or ALL objects if a list
    NOT_CONTAINS_ANY_OBJECTS("NOT_CONTAINS_(ANY)_OBJECT(S)",
                             false), // Not contains the object or any of the objects if a list
    CONTAINS_ANY_OBJECTS("CONTAINS_ANY_OBJECT(S)", false), // Contains any of the objects of the list

    EXISTS_FIELD("EXISTS_FIELD", false);

    private final String  operator;
    private final boolean allowsNestedFilters;

    FilterOperator(String operator, boolean allowsNestedFilters) {
        this.operator            = operator;
        this.allowsNestedFilters = allowsNestedFilters;
    }

    public static FilterOperator fromValue(String value) {
        return Arrays.stream(FilterOperator.values())
                .filter(operator -> operator.getOperator().equalsIgnoreCase(value))
                .findFirst()
                .orElseGet(() -> fromName(value));

    }

    private static FilterOperator fromName(String value) {
        try {
            return FilterOperator.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new WrongFormat("Operator " + value + " not exists");
        }
    }

    public String getOperator() {
        return operator;
    }

    public boolean isAllowsNestedFilters() {
        return allowsNestedFilters;
    }
}
