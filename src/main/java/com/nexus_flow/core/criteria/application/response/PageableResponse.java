package com.nexus_flow.core.criteria.application.response;

import com.nexus_flow.core.cqrs.domain.query.Response;
import com.nexus_flow.core.criteria.domain.page.Pageable;
import lombok.Value;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Value
public class PageableResponse<R extends Response> implements Response {

    int     number;
    int     size;
    int     totalPages;
    int     numberOfElements;
    long    totalElements;
    boolean previousPage;
    boolean firstPage;
    boolean nextPage;
    boolean lastPage;
    List<R> content;

    public static <T, R extends Response> PageableResponse<R> fromDomain(Pageable<T> aggregateResponse,
                                                                         Function<T, R> domainToResponseConverter) {

        return new PageableResponse<>(aggregateResponse.getPage().getNumber().getValue(),
                                      aggregateResponse.getNumberOfElements(),
                                      aggregateResponse.getTotalPages(),
                                      aggregateResponse.getNumberOfElements(),
                                      aggregateResponse.getTotalResults(),
                                      aggregateResponse.hasPrevious(),
                                      aggregateResponse.isFirst(),
                                      aggregateResponse.hasNext(),
                                      aggregateResponse.isLast(),
                                      aggregateResponse.getResults().stream()
                                              .map(domainToResponseConverter)
                                              .collect(Collectors.toList()));
    }
}
