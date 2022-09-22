package com.nexus_flow.core.criteria.app;

import com.nexus_flow.core.criteria.application.query.PageQuery;
import com.nexus_flow.core.criteria.application.query.SortQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageRequest implements Serializable {
    @Schema(example = "0")
    private int          page;
    @Schema(example = "50")
    private int          size;
    @Schema(example = "id,name,asc")
    private List<String> sort;

    public PageQuery toPageQuery() {
        return new PageQuery(page, size, SortQuery.fromRequest(sort));
    }
}
