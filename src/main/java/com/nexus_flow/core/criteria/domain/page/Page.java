package com.nexus_flow.core.criteria.domain.page;


import com.nexus_flow.core.criteria.application.query.PageQuery;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode
public class Page {

    private final Sort       sort;
    private       PageNumber number;
    private       PageSize   size;

    public Page(PageNumber number, PageSize size, Sort sort) {
        this.number = number;
        this.size   = size;
        this.sort   = sort;
    }

    public Page() {
        this.sort = Sort.unsorted();
    }

    public static Page fromQuery(PageQuery pageQuery) {
        if (pageQuery == null) return new Page();
        Sort sort = pageQuery.getSort() == null ? Sort.unsorted() : Sort.fromQuery(pageQuery.getSort());
        return new Page(new PageNumber(pageQuery.getNumber()),
                        new PageSize(pageQuery.getSize()),
                        sort
        );
    }

    public static Page create() {
        return unpaged();
    }

    public static Page unpaged() {
        return new Page(new PageNumber(0),
                        new PageSize(0),
                        Sort.unsorted());
    }


    public Page number(int number) {
        this.number = new PageNumber(number);
        return this;
    }

    public Page size(int size) {
        this.size = new PageSize(size);
        return this;
    }

    public Page sort(String field, String direction) {
        this.sort.addOrder(new SortBy(field), SortDirection.valueOf(direction));
        return this;
    }

    public boolean isUnpaged() {
        return number == null ||
                size == null || 0 == size.getValue();
    }

    public boolean isPaged() {
        return !isUnpaged();
    }


    public boolean isUnsorted() {
        return sort.isUnsorted();
    }

    public boolean isSorted() {
        return !isUnsorted();
    }

}

