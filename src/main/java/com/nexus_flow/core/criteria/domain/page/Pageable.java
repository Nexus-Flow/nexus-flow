package com.nexus_flow.core.criteria.domain.page;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.emptyList;

@Getter
@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor
public class Pageable<T> {

    /**
     * "number": 1,
     * "size": 50,
     * "totalPages": 46669,
     * "numberOfElements": 50,
     * "totalElements": 2333435,
     * "previousPage": true,
     * "firstPage": false,
     * "nextPage": true,
     * "lastPage": false,
     */

    private List<T> results;
    private long    totalResults;
    private Page    page;

    public static Pageable empty() {
        return new Pageable<>(emptyList(), 0, Page.unpaged());
    }

    public List<T> getResults() {
        return new ArrayList<>(results);
    }

    public int getNumberOfElements() {
        return getResults().size();
    }

    public int getTotalPages() {
        return this.page.getSize().getValue() == 0 ? 1 : (int) Math.ceil(this.totalResults / (double) this.getPage().getSize().getValue());
    }

    public boolean hasNext() {
        return page.isPaged() && this.page.getNumber().getValue() + 1 < this.getTotalPages();
    }

    public boolean hasPrevious() {
        return this.page.getNumber().getValue() > 0;
    }

    public boolean isLast() {
        return page.isPaged() && !this.hasNext();
    }

    public boolean isFirst() {
        return page.isUnpaged() || 0 == page.getNumber().getValue();
    }


    public Page nextPage() {
        return hasNext() ? page.number(1 + page.getNumber().getValue()) : this.page;
    }

    public Page previousPage() {
        return page.getNumber().getValue() == 0 ? this.page : page.number(1 - page.getNumber().getValue());
    }

    public Page first() {
        return page.number(0);
    }


}

