package com.nexus_flow.core.criteria.domain.page;

import com.nexus_flow.core.criteria.application.query.SortQuery;
import lombok.Value;

import java.util.List;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;

@Value
public class Sort {
    List<Order> orderList;

    public static Sort unsorted() {
        return new Sort(emptyList());
    }

    public static Sort fromQuery(SortQuery sortQuery) {
        return new Sort(sortQuery.getOrderList().stream()
                                .map(order -> new Order(new SortBy(order.getField()),
                                                        SortDirection.valueOf(order.getDirection().toUpperCase())))
                                .collect(Collectors.toList()));
    }

    public void addOrder(SortBy field, SortDirection direction) {
        this.orderList.add(new Order(field, direction));
    }


    public boolean isUnsorted() {
        return this.orderList.isEmpty();
    }


    @Value
    public static class Order {
        SortBy        field;
        SortDirection direction;
    }

}
