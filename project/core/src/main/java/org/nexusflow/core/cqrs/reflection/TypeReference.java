package org.nexusflow.core.cqrs.reflection;

import javax.lang.model.type.IntersectionType;
import java.lang.reflect.*;
import java.util.Objects;

public class TypeReference<T> {
    private final Type type;
    private final TypeCategory typeCategory;

    public TypeReference(Type type) {
        this.typeCategory = determineTypeCategory(type);
        this.type = determineType(type);
    }

    private TypeCategory determineTypeCategory(Type type) {
        return switch (type) {
            case Class<?> _ -> TypeCategory.CLASS;
            case ParameterizedType _ -> TypeCategory.PARAMETERIZED_TYPE;
            case GenericArrayType _ -> TypeCategory.GENERIC_ARRAY_TYPE;
            case WildcardType _ -> TypeCategory.WILDCARD_TYPE;
            case TypeVariable<?> _ -> TypeCategory.TYPE_VARIABLE;
            case IntersectionType _ -> TypeCategory.INTERSECTION_TYPE;
            case null, default -> TypeCategory.OTHER;
        };
    }

    private Type determineType(Type type) {
        TypeReferenceResolver resolver = TypeReferenceResolverFactory.getResolver(typeCategory);
        return resolver.resolve(type);
    }

    public Type getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TypeReference<?> other = (TypeReference<?>) o;
        return type.equals(other.type);
    }


    @Override
    public int hashCode() {
        return Objects.hash(type);
    }

}