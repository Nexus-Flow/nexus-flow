package com.nexus_flow.core.criteria.app;

import com.nexus_flow.core.criteria.application.query.CriterionQuery;

import java.io.Serializable;

public interface CriterionRequest extends Serializable {
    <R extends CriterionQuery> R toCriteriaQuery();
}
