package com.nexus_flow.core.criteria.domain.filter;


import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FilterValue<Y extends Comparable<? super Y>> {

    private final List<Y> values;


    public FilterValue(List<Y> values) {
        this.values = values;
    }

    public FilterValue(Y value) {
        this.values = new ArrayList<>();
        this.values.add(value);
    }

    public List<Y> getValues() {
        return values;
    }

    public Y getFirst() {
        if (values.isEmpty()) {
            throw new InvalidFilterValue("INVALID_VALUE",
                                         "Not enough values in filter");
        }
        return values.get(0);
    }

    public Y getSecond() {
        if (values.size() < 2) {
            throw new InvalidFilterValue("INVALID_VALUE",
                                         "Not enough values in filter");
        }
        return values.get(1);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FilterValue<?> that = (FilterValue<?>) o;
        return Objects.equals(values, that.values);
    }
}
