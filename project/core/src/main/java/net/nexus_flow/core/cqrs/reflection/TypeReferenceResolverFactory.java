package net.nexus_flow.core.cqrs.reflection;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;

public class TypeReferenceResolverFactory {
    private static final Map<TypeCategory, TypeReferenceResolver> resolvers = new HashMap<>();

    static {
        resolvers.put(TypeCategory.CLASS, TypeReferenceResolverFactory::resolveClassType);
        resolvers.put(TypeCategory.PARAMETERIZED_TYPE, TypeReferenceResolverFactory::resolveParameterizedType);
        resolvers.put(TypeCategory.GENERIC_ARRAY_TYPE, TypeReferenceResolverFactory::resolveGenericArrayType);
        resolvers.put(TypeCategory.WILDCARD_TYPE, TypeReferenceResolverFactory::resolveWildcardType);
        resolvers.put(TypeCategory.TYPE_VARIABLE, TypeReferenceResolverFactory::resolveTypeVariable);
        resolvers.put(TypeCategory.INTERSECTION_TYPE, TypeReferenceResolverFactory::resolveIntersectionType);
        resolvers.put(TypeCategory.OTHER, TypeReferenceResolverFactory::resolveOtherType);
    }

    public static TypeReferenceResolver getResolver(TypeCategory category) {
        return resolvers.get(category);
    }

    private static Type resolveClassType(Type type) {
        return type;
    }

    public static Type resolveParameterizedType(Type type) {
        ParameterizedType pt = (ParameterizedType) type;
        return new ParameterizedTypeReference(pt.getRawType(), pt.getActualTypeArguments());
    }

    private static Type resolveGenericArrayType(Type type) {
        return type;
    }

    private static Type resolveWildcardType(Type type) {
        return type;
    }

    private static Type resolveTypeVariable(Type type) {
        TypeVariable<?> pt = (TypeVariable<?>) type;
        return new TypeReferenceVariable<>(type.getTypeName(), pt.getGenericDeclaration(), pt.getBounds(), pt.getAnnotatedBounds());
    }

    private static Type resolveIntersectionType(Type type) {
        return type;
    }

    private static Type resolveOtherType(Type type) {
        return type;
    }
}