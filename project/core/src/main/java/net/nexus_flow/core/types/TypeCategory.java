package net.nexus_flow.core.types;

import java.lang.reflect.Type;

/**
 * Coarse classification of generic type categories.
 *
 * <p>Instances of {@link TypeReference} are classified into one of these categories to determine
 * which resolver strategy to apply during type introspection.
 *
 * @see TypeReferenceResolver
 * @see TypeReferenceResolverFactory
 */
public enum TypeCategory {
    /** Plain class types (e.g., {@code String.class}, {@code Integer.class}). */
    CLASS,

    /** Parameterised types (e.g., {@code List<String>}, {@code Map<String, Integer>}). */
    PARAMETERIZED_TYPE,

    /** Generic array types (e.g., {@code List<T>[]}, {@code T[]}). */
    GENERIC_ARRAY_TYPE,

    /** Wildcard types (e.g., {@code List<? extends Number>}, {@code Map<String, ?>}). */
    WILDCARD_TYPE,

    /** Type variables (e.g., the {@code T} in {@code class Foo<T>}). */
    TYPE_VARIABLE,

    /** Intersection types (e.g., {@code T extends A & B} for type variable {@code T}). */
    INTERSECTION_TYPE,

    /** Other {@link Type} implementations not explicitly handled above. */
    OTHER
}
