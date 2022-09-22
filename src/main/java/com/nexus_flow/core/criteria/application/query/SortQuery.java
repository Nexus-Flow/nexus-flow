package com.nexus_flow.core.criteria.application.query;

import com.nexus_flow.core.ddd.exceptions.WrongFormat;
import lombok.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Value
public class SortQuery {
    List<Order> orderList;

    public SortQuery() {
        orderList = new ArrayList<>();
    }

    public static SortQuery fromRequest(List<String> sortRequest) {
        SortQuery sortQuery = new SortQuery();
        if (sortRequest == null) {
            return sortQuery;
        }
        List<String> orderFields = new ArrayList<>();
        for (String sort : sortRequest) {
            if (sort.contains(",")) {
                sortQuery.getOrderList().addAll(parseGroupedSort(sort));
            } else {
                if ("asc".equalsIgnoreCase(sort) || "desc".equalsIgnoreCase(sort)) {
                    sortQuery.getOrderList().addAll(orderFields.stream()
                                                            .map(field -> new SortQuery.Order(field, sort))
                                                            .collect(Collectors.toList()));
                    orderFields.clear();
                } else {
                    orderFields.add(sort);
                }
            }
        }
        return sortQuery;
    }

    private static List<Order> parseGroupedSort(String sort) {
        String[] queryFields = sort.split(",");
        int      length      = queryFields.length;
        if (length < 2 ||
                !("asc".equalsIgnoreCase(queryFields[length - 1]) ||
                        "desc".equalsIgnoreCase(queryFields[length - 1]))) {
            throw new WrongFormat(
                    "Sort query must be a list of properties ending with [asc|desc] example -> \"id,name,asc\"");
        }
        return IntStream.range(0, length - 1)
                .mapToObj(i -> new SortQuery.Order(queryFields[i], queryFields[length - 1]))
                .collect(Collectors.toList());
    }


    @Value
    public static class Order {
        String field;
        String direction;
    }
}
