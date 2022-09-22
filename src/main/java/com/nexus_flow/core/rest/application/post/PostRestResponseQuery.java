package com.nexus_flow.core.rest.application.post;

import com.nexus_flow.core.cqrs.domain.query.Query;
import com.nexus_flow.core.cqrs.domain.query.Response;
import com.nexus_flow.core.ddd.exceptions.WrongFormat;
import com.nexus_flow.core.rest.application.query_aux.PathVariableQuery;
import com.nexus_flow.core.rest.application.query_aux.RequestQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PostRestResponseQuery implements Query {

    private List<PathVariableQuery>   pathVariableQueries = new ArrayList<>();
    private RequestQuery requestQuery;
    private Class<? extends Response> response;
    private Boolean                   withRetry;

    public PostRestResponseQuery(PostRestResponseQueryBuilder postRestResponseQueryBuilder) {
        pathVariableQueries = postRestResponseQueryBuilder.pathVariableQueries;
        requestQuery        = postRestResponseQueryBuilder.requestQuery;
        response            = postRestResponseQueryBuilder.response;
        withRetry           = postRestResponseQueryBuilder.withRetry;
    }

    public static PostRestResponseQueryBuilder builder() {
        return new PostRestResponseQueryBuilder();
    }

    public static PathVariableQuery.PathVariableBuilder pathVariableBuilder() {
        return new PathVariableQuery.PathVariableBuilder();
    }

    public static RequestQuery.RequestQueryBuilder requestQueryBuilder() {
        return new RequestQuery.RequestQueryBuilder();
    }

    public List<PathVariableQuery> getPathVariableQueries() {
        return pathVariableQueries;
    }

    public RequestQuery getRequestQuery() {
        return requestQuery;
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
        PostRestResponseQuery that = (PostRestResponseQuery) o;
        return Objects.equals(pathVariableQueries, that.pathVariableQueries) &&
                Objects.equals(requestQuery, that.requestQuery) &&
                Objects.equals(response, that.response) &&
                Objects.equals(withRetry, that.withRetry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pathVariableQueries, requestQuery, response, withRetry);
    }

    public static final class PostRestResponseQueryBuilder {
        private List<PathVariableQuery>   pathVariableQueries = new ArrayList<>();
        private RequestQuery              requestQuery;
        private Class<? extends Response> response;
        private Boolean                   withRetry;

        public PostRestResponseQueryBuilder() {
        }

        public PostRestResponseQueryBuilder withResponse(Class<? extends Response> val) {
            if (response != null) throw new WrongFormat("Query already has a response");
            response = val;
            return this;
        }

        public PostRestResponseQueryBuilder withPathVariable(PathVariableQuery pathVariableQuery) {
            pathVariableQueries.add(pathVariableQuery);
            return this;
        }

        public PostRestResponseQueryBuilder withRequest(RequestQuery requestQuery) {
            this.requestQuery = requestQuery;
            return this;
        }

        public PostRestResponseQueryBuilder withPathVariableIdWithValue(String pathVariableValue) {
            pathVariableQueries.add(new PathVariableQuery("id", pathVariableValue));
            return this;
        }

        public PostRestResponseQueryBuilder withRetry(Boolean withRetry) {
            this.withRetry = withRetry;
            return this;
        }

        public PostRestResponseQuery build() {
            return new PostRestResponseQuery(this);
        }
    }

}
