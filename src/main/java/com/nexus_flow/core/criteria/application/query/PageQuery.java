package com.nexus_flow.core.criteria.application.query;


import com.nexus_flow.core.criteria.app.PageRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageQuery {
    private int       number;
    private int       size;
    private SortQuery sort;

    public static PageQuery fromRequest(PageRequest pageRequest) {
        return new PageQuery(pageRequest.getPage(),
                             pageRequest.getSize(),
                             SortQuery.fromRequest(pageRequest.getSort()));
    }

    public static PageQuery unpaged() {
        return new PageQuery(0, 0, new SortQuery());
    }


}

