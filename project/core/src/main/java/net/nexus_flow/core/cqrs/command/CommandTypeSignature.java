package net.nexus_flow.core.cqrs.command;

import net.nexus_flow.core.cqrs.reflection.TypeReference;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

abstract class CommandTypeSignature<T, R> {
    private final TypeReference<T> typeReference;
    private final TypeReference<R> returnType;

    protected CommandTypeSignature() {
        Type superClass = getClass().getGenericSuperclass();
        if (!(superClass instanceof ParameterizedType parameterizedType)) {
            throw new RuntimeException("CommandTypeSignature must be parameterized with concrete classes.");
        }
        Type[] types = parameterizedType.getActualTypeArguments();
        this.typeReference = new TypeReference<>(types[0]);
        this.returnType = types.length > 1 ? new TypeReference<>(types[1]) : new TypeReference<>(Void.class);
    }

    public final TypeReference<T> getCommandType() {
        return typeReference;
    }

    public final TypeReference<R> getReturnType() {
        return returnType;
    }

}
