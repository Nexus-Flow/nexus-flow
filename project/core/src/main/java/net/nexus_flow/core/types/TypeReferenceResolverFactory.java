package net.nexus_flow.core.types;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Factory for obtaining {@link TypeReferenceResolver} instances by type category.
 *
 * <p>This class maintains a static map of resolver strategies, each tailored to a specific {@link
 * TypeCategory}. It is used by {@link TypeReference} to resolve captured types during
 * introspection.
 *
 * <p>Cannot be instantiated; all resolvers are accessed via the static factory method {@link
 * #getResolver(TypeCategory)}.
 *
 * @see TypeReferenceResolver
 * @see TypeCategory
 */
public class TypeReferenceResolverFactory {
    private static final Map<TypeCategory, TypeReferenceResolver> resolvers =
            new EnumMap<>(TypeCategory.class);

    static {
        resolvers.put(TypeCategory.CLASS, TypeReferenceResolverFactory::resolveClassType);
        resolvers.put(
                      TypeCategory.PARAMETERIZED_TYPE, TypeReferenceResolverFactory::resolveParameterizedType);
        resolvers.put(
                      TypeCategory.GENERIC_ARRAY_TYPE, TypeReferenceResolverFactory::resolveGenericArrayType);
        resolvers.put(TypeCategory.WILDCARD_TYPE, TypeReferenceResolverFactory::resolveWildcardType);
        resolvers.put(TypeCategory.TYPE_VARIABLE, TypeReferenceResolverFactory::resolveTypeVariable);
        resolvers.put(
                      TypeCategory.INTERSECTION_TYPE, TypeReferenceResolverFactory::resolveIntersectionType);
        resolvers.put(TypeCategory.OTHER, TypeReferenceResolverFactory::resolveOtherType);
    }

    /**
     * Prevent instantiation of this factory class.
     *
     * <p>All resolvers are accessed through the static {@link #getResolver(TypeCategory)} method.
     */
    private TypeReferenceResolverFactory() {
        // Prevent instantiation
    }

    /**
     * Obtains the resolver for a given type category.
     *
     * <p>The resolver is looked up from a static map populated at class initialisation time.
     *
     * @param category the {@link TypeCategory} to resolve for
     * @return the {@link TypeReferenceResolver} for the category (never {@code null})
     * @throws NullPointerException     if {@code category} is {@code null}
     * @throws IllegalArgumentException if the category is not registered
     */
    public static TypeReferenceResolver getResolver(TypeCategory category) {
        TypeCategory          nonNullCategory = Objects.requireNonNull(category, "category");
        TypeReferenceResolver resolver        = resolvers.get(nonNullCategory);
        if (resolver == null) {
            throw new IllegalArgumentException(
                    "No resolver registered for type category: " + nonNullCategory);
        }
        return resolver;
    }

    /**
     * Resolver for plain class types.
     *
     * <p>Class types are returned as-is (no wrapping needed).
     *
     * @param type the class type
     * @return the class type unchanged
     */
    private static Type resolveClassType(Type type) {
        return type;
    }

    /**
     * Resolver for parameterised types.
     *
     * <p>Parameterised types are wrapped in a {@link ParameterizedTypeReference} for consistent
     * handling and to ensure correct equality and hashing semantics.
     *
     * @param type the parameterised type
     * @return a {@link ParameterizedTypeReference} wrapping the type
     * @throws IllegalArgumentException if {@code type} is not a {@link ParameterizedType}
     */
    public static Type resolveParameterizedType(Type type) {
        if (!(type instanceof ParameterizedType pt)) {
            throw new IllegalArgumentException("Expected ParameterizedType but got: " + type);
        }
        return new ParameterizedTypeReference(pt.getRawType(), pt.getActualTypeArguments());
    }

    /**
     * Resolver for generic array types.
     *
     * <p>Generic array types are returned as-is (no wrapping needed).
     *
     * @param type the generic array type
     * @return the generic array type unchanged
     */
    private static Type resolveGenericArrayType(Type type) {
        return type;
    }

    /**
     * Resolver for wildcard types.
     *
     * <p>Wildcard types are returned as-is (no wrapping needed).
     *
     * @param type the wildcard type
     * @return the wildcard type unchanged
     */
    private static Type resolveWildcardType(Type type) {
        return type;
    }

    /**
     * Resolver for type variables.
     *
     * <p>Type variables are wrapped in a {@link TypeReferenceVariable} to provide a consistent
     * reference implementation with proper equality and hashing semantics.
     *
     * @param type the type variable
     * @return a {@link TypeReferenceVariable} wrapping the type variable
     */
    private static Type resolveTypeVariable(Type type) {
        TypeVariable<?> pt = (TypeVariable<?>) type;
        return new TypeReferenceVariable<>(
                type.getTypeName(), pt.getGenericDeclaration(), pt.getBounds(), pt.getAnnotatedBounds());
    }

    /**
     * Resolver for intersection types.
     *
     * <p>At runtime Java exposes intersection bounds through {@link TypeVariable} instances with
     * multiple bounds, so this resolver reuses the same wrapper as plain type variables.
     *
     * @param type the intersection type carrier
     * @return a {@link TypeReferenceVariable} when the carrier is a {@link TypeVariable}; otherwise
     *         the original type unchanged
     */
    private static Type resolveIntersectionType(Type type) {
        if (type instanceof TypeVariable<?>) {
            return resolveTypeVariable(type);
        }
        return type;
    }

    /**
     * Resolver for other {@link Type} implementations.
     *
     * <p>Unknown type implementations are returned as-is (fallback for future {@code Type} subtypes).
     *
     * @param type the type
     * @return the type unchanged
     */
    private static Type resolveOtherType(Type type) {
        return type;
    }
}
