package com.nexus_flow.core.criteria.infrastructure.jpa;


import com.nexus_flow.core.criteria.domain.page.Page;
import com.nexus_flow.core.criteria.domain.page.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.stream.Collectors;

public class JpaPageMapper<T> {

    public org.springframework.data.domain.Pageable toJpaPageable(Page page) {
        if (page.isUnpaged()) {
            return org.springframework.data.domain.Pageable.unpaged();
        }
        if (page.isSorted()) {
            return PageRequest.of(page.getNumber().getValue(),
                                  page.getSize().getValue(),
                                  Sort.by(page.getSort().getOrderList().stream()
                                                  .map(order -> new Sort.Order(Sort.Direction.fromString(order.getDirection().value()),
                                                                               order.getField().getValue()))
                                                  .collect(Collectors.toList())));
        }
        return PageRequest.of(page.getNumber().getValue(),
                              page.getSize().getValue());
    }

    public Pageable<T> toDomain(org.springframework.data.domain.Page<T> jpaPage) {
        Page page = new Page()
                .number(jpaPage.getNumber())
                .size(jpaPage.getSize());

        return new Pageable<>(jpaPage.getContent(), jpaPage.getTotalElements(), page);
    }
}
