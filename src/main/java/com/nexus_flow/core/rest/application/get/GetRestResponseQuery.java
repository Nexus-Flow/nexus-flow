package com.nexus_flow.core.rest.application.get;

import com.nexus_flow.core.cqrs.domain.query.Query;
import com.nexus_flow.core.cqrs.domain.query.Response;
import com.nexus_flow.core.ddd.exceptions.WrongFormat;
import com.nexus_flow.core.rest.application.query_aux.PathVariableQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class GetRestResponseQuery implements Query {

    private List<PathVariableQuery>   pathVariableQueries = new ArrayList<>();
    private Class<? extends Response> response;
    private Boolean                   withRetry;

    public GetRestResponseQuery(GetRestResponseQueryBuilder getRestResponseQueryBuilder) {
        pathVariableQueries = getRestResponseQueryBuilder.pathVariableQueries;
        response            = getRestResponseQueryBuilder.response;
        withRetry           = getRestResponseQueryBuilder.withRetry;
    }

    public static GetRestResponseQueryBuilder builder() {
        return new GetRestResponseQueryBuilder();
    }

    public static PathVariableQuery.PathVariableBuilder pathVariableBuilder() {
        return new PathVariableQuery.PathVariableBuilder();
    }

    public List<PathVariableQuery> getPathVariableQueries() {
        return pathVariableQueries;
    }

    public Class<? extends Response> getResponse() {
        return response;
    }

    // Note: not auto-generated
    public Boolean getWithRetry() {
        return withRetry != null ? withRetry : Boolean.TRUE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GetRestResponseQuery that = (GetRestResponseQuery) o;
        return Objects.equals(pathVariableQueries, that.pathVariableQueries) &&
                Objects.equals(response, that.response) &&
                Objects.equals(withRetry, that.withRetry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pathVariableQueries, response, withRetry);
    }

    public static final class GetRestResponseQueryBuilder {
        private List<PathVariableQuery>   pathVariableQueries = new ArrayList<>();
        private Class<? extends Response> response;
        private Boolean                   withRetry;

        public GetRestResponseQueryBuilder() {
        }

        public GetRestResponseQueryBuilder withResponse(Class<? extends Response> val) {
            if (response != null) throw new WrongFormat("Query already has a response");
            response = val;
            return this;
        }

        public GetRestResponseQueryBuilder withPathVariable(PathVariableQuery pathVariableQuery) {
            pathVariableQueries.add(pathVariableQuery);
            return this;
        }

        public GetRestResponseQueryBuilder withPathVariableIdWithValue(String pathVariableValue) {
            pathVariableQueries.add(new PathVariableQuery("id", pathVariableValue));
            return this;
        }

        public GetRestResponseQueryBuilder withRetry(Boolean withRetry) {
            this.withRetry = withRetry;
            return this;
        }

        public GetRestResponseQuery build() {
            return new GetRestResponseQuery(this);
        }
    }

}
