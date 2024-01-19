package org.nexusflow.core.cqrs.reflection;

import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.StringJoiner;

public final class ParameterizedTypeReference implements ParameterizedType, Serializable {
    private final Type rawType;
    private final Type[] typeArguments;

    public ParameterizedTypeReference(Type rawType, Type[] typeArguments) {
        this.rawType = rawType;
        this.typeArguments = typeArguments;
    }

    public String getTypeName() {
        String typeName = this.rawType.getTypeName();
        if (this.typeArguments.length == 0) {
            return typeName;
        } else {
            String formattedTypeArguments = getFormattedTypeArguments();
            return typeName + formattedTypeArguments;
        }
    }

    private String getFormattedTypeArguments() {
        StringJoiner stringJoiner = new StringJoiner(", ", "<", ">");
        for (Type argument : this.typeArguments) {
            stringJoiner.add(argument.getTypeName());
        }
        return stringJoiner.toString();
    }

    public Type getOwnerType() {
        return null;
    }

    public Type getRawType() {
        return this.rawType;
    }

    public Type[] getActualTypeArguments() {
        return this.typeArguments;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other instanceof ParameterizedType that) {
            return this.rawType.equals(that.getRawType()) &&
                    Arrays.equals(this.typeArguments, that.getActualTypeArguments());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return this.rawType.hashCode() * 31 + Arrays.hashCode(this.typeArguments);
    }

    @Override
    public String toString() {
        return this.getTypeName();
    }
}