package net.nexus_flow.core.types;

import java.lang.reflect.Type;

/**
 * Strategy for resolving a {@link Type} during type reference introspection.
 *
 * <p>Different categories of types (classes, parameterised types, type variables, etc.) may require
 * different resolution strategies. This interface defines the common contract.
 *
 * <p>Implementations are typically obtained from {@link TypeReferenceResolverFactory} based on the
 * {@link TypeCategory} of the type being resolved.
 *
 * @see TypeReferenceResolverFactory
 * @see TypeCategory
 */
@FunctionalInterface
public interface TypeReferenceResolver {
    /**
     * Resolves a {@link Type}.
     *
     * <p>Some types are returned as-is; others (such as parameterised types) may be wrapped in a
     * reference implementation for consistent handling across the framework.
     *
     * @param type the {@link Type} to resolve (must not be {@code null})
     * @return the resolved {@link Type} (typically the input or a wrapper around it)
     */
    Type resolve(Type type);
}
