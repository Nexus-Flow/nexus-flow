package com.nexus_flow.core.rest.application.query_aux;

import com.nexus_flow.core.ddd.Utils;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

public  class RequestQuery {

    private Map<String, Serializable> body;

    private RequestQuery() {
    }

    private RequestQuery(Map<String, Serializable> body) {
        this.body = body;
    }

    public Map<String, Serializable> toMap() {
        return Utils.toMapStringSerializable(this);
    }

    public Map<String, Serializable> getBody() {
        return body;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RequestQuery query = (RequestQuery) o;
        return Objects.equals(body, query.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(body);
    }

    public static final class RequestQueryBuilder {
        Map<String, Serializable> body;

        public RequestQueryBuilder() {
        }

        public RequestQuery.RequestQueryBuilder withBody(Map<String, Serializable> body) {
            this.body = body;
            return this;
        }

        public RequestQuery build() {
            return new RequestQuery(this.body);
        }
    }
}
