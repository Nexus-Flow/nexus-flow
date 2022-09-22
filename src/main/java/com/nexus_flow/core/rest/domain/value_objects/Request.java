package com.nexus_flow.core.rest.domain.value_objects;

import com.nexus_flow.core.ddd.exceptions.WrongFormat;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

public class Request {

    private Map<String, Serializable> body;

    public Request(Map<String, Serializable> params) {
        checkPrimitivesMap(params);
        this.body = params;
    }

    private void checkPrimitivesMap(Map<String, Serializable> map) {
        if (map == null) {
            throw new WrongFormat("Map of primitives to create Request can't be null");
        }
    }

    public Map<String, Serializable> getBody() {

        return body;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Request that = (Request) o;
        return Objects.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(body);
    }
}
