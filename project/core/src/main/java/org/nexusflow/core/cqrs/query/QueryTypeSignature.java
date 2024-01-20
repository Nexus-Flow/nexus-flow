package org.nexusflow.core.cqrs.query;

import org.nexusflow.core.cqrs.reflection.TypeReference;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

abstract class QueryTypeSignature<T, R> {
    private final TypeReference<T> typeReference;
    private final TypeReference<R> returnType;

    protected QueryTypeSignature() {
        Type superClass = getClass().getGenericSuperclass();
        if (!(superClass instanceof ParameterizedType parameterizedType)) {
            throw new RuntimeException("QueryTypeSignature must be parameterized with concrete classes.");
        }
        Type[] types = parameterizedType.getActualTypeArguments();
        if (types.length <= 1) {
            throw new RuntimeException("The both type parameters should not be empty.");
        }
        this.typeReference = new TypeReference<>(types[0]);
        this.returnType = new TypeReference<>(types[1]);
    }

    public final TypeReference<T> getQueryType() {
        return typeReference;
    }

    public final TypeReference<R> getReturnType() {
        return returnType;
    }

}
