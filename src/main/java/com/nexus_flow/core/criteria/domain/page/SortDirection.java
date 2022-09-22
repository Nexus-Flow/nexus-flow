package com.nexus_flow.core.criteria.domain.page;

public enum SortDirection {
    ASC("asc"),
    DESC("desc"),
    NONE("none");
    private final String type;

    SortDirection(String type) {
        this.type = type;
    }

    public boolean isNone() {
        return this == NONE;
    }

    public boolean isAsc() {
        return this == ASC;
    }

    public String value() {
        return type;
    }
}

