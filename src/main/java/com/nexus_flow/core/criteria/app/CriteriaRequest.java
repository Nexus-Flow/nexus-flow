package com.nexus_flow.core.criteria.app;


import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@JsonDeserialize(using = CriteriaRequestJsonParser.CriteriaRequestJsonDeserializer.class)
@JsonSerialize(using = CriteriaRequestJsonParser.CriteriaRequestJsonSerializer.class)
public abstract class CriteriaRequest<F extends CriterionRequest> implements CriterionRequest, Serializable {

    @Schema(example = "{ \n" +
            "    \"and\":[ \n" +
            "        {\n" +
            "            \"field\": \"nexus_flow.id\", \n" +
            "            \"filterType\": \"CONTAINS\", \n" +
            "            \"value\": \"TEST1\"\n" +
            "        }, \n" +
            "        {\n" +
            "            \"or\": [\n" +
            "                {\n" +
            "                    \"field\": \"nexus_flow.status\",\n" +
            "                    \"operator\": \"IN\" ,\n" +
            "                    \"value\": [ \n" +
            "                        \"IN_CREATION\",    \n" +
            "                        \"DELETED\" \n" +
            "                    ], \n" +
            "                }, \n" +
            "                { \n" +
            "                    \"field\": \"nexus_flow.id\", \n" +
            "                    \"filterType\": \"CONTAINS\" ,\n" +
            "                    \"value\": \"TEST2\" \n" +
            "                }]} \n" +
            "        ] \n" +
            "}")
    protected List<F> filters = new ArrayList<>();

    protected CriteriaRequest() {

    }

    protected CriteriaRequest(List<F> criteriaList) {
        this.filters.addAll(criteriaList);
    }

    @SafeVarargs
    protected CriteriaRequest(F... criteria) {
        this.filters.addAll(Arrays.asList(criteria));
    }

    public final CriteriaRequest<F> addCriteria(List<F> criteria) {
        this.filters.addAll(criteria);
        return this;
    }

    @SafeVarargs
    public final CriteriaRequest<F> addCriteria(F... criteria) {
        this.filters.addAll(Arrays.asList(criteria));
        return this;
    }


    public final CriteriaRequest<F> empty() {
        filters.clear();
        return this;
    }

    public <Q extends CriterionRequest> List<Q> getFilters() {
        return (List<Q>) filters;
    }


}