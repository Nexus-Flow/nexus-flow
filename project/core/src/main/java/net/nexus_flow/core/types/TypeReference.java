package net.nexus_flow.core.types;

import java.lang.reflect.*;
import java.util.Objects;

/**
 * Opaque super-type-token capturing a parameterised generic type at compile time.
 *
 * <p>This class uses Neal Gafter's super-type-token pattern (same as Guava's {@code TypeToken} and
 * Jackson's {@code TypeReference}) to recover parameterised types across Java's generic type
 * erasure boundary. The {@linkplain #TypeReference() no-arg constructor} must be invoked as an
 * anonymous subclass so the JVM records the actual type argument in the {@code Signature}
 * attribute, which {@link Class#getGenericSuperclass()} exposes at runtime.
 *
 * <p>Instances are effectively immutable with cached hash codes:
 *
 * <ul>
 * <li>Both {@code type} and {@code typeCategory} are final and set in the constructor.
 * <li>The hash code is computed once and cached for the lifetime of the instance, eliminating
 * per-call boxing and varargs array allocation on map lookups in type registries.
 * <li>Safe for use as map keys in concurrent environments.
 * </ul>
 *
 * <p>Equality is based on the underlying {@link Type}, with a fast-path identity check on {@code
 * Class{@code <?}} instances (which the JVM typically interns).
 *
 * @param <T> the actual generic type being captured (erased at runtime, but recoverable via the
 *            super-type token pattern)
 * @see ParameterizedTypeReference
 * @see TypeReferenceResolver
 */
public class TypeReference<T> {
    private final Type         type;
    private final TypeCategory typeCategory;
    // TypeReference is effectively immutable (both `type`
    // and `typeCategory` are final and set in the constructor). The
    // hash is computed once and cached for the lifetime of the
    // instance, eliminating the per-call boxing+vararg allocation of
    // Objects.hash(type) on every map lookup in the registries.
    private final int cachedHash;

    /**
     * Construct a {@code TypeReference} from an explicit {@link Type}.
     *
     * <p>Use this constructor when the type is known at instantiation time (e.g., a {@link Class} or
     * {@link ParameterizedType}). For capturing parameterised types generically, use the no-arg
     * {@linkplain #TypeReference() super-type-token constructor} as an anonymous subclass.
     *
     * @param type the {@link Type} to capture (must not be {@code null})
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public TypeReference(Type type) {
        Type nonNullType = Objects.requireNonNull(type, "type");
        this.typeCategory = determineTypeCategory(nonNullType);
        this.type         = determineType(nonNullType);
        this.cachedHash   = this.type.hashCode();
    }

    /**
     * Super-type token constructor. Use as an anonymous subclass so the JVM records the actual type
     * argument in the {@code Signature} attribute, which {@link Class#getGenericSuperclass()} exposes
     * at runtime. This is the same pattern Guava's {@code TypeToken} and Jackson's {@code
     * TypeReference} use to recover parameterised types across Java's generic erasure boundary.
     *
     * <p>
     *
     * {@snippet :
     * // Capture a parameterized type token for later reflection
     * TypeReference<java.util.List<String>> listOfNames =
     *         new TypeReference<java.util.List<String>>() {
     *         };
     *
     * Type capturedType = listOfNames.getType();
     * // capturedType now represents java.util.List<String>
     * }
     *
     * @throws IllegalStateException when invoked directly (not as an anonymous subclass), because the
     *                               generic superclass would not be a {@link ParameterizedType}.
     */
    protected TypeReference() {
        Type superClass = getClass().getGenericSuperclass();
        if (!(superClass instanceof ParameterizedType parameterized)) {
            throw new IllegalStateException(
                    "TypeReference must be instantiated as an anonymous subclass so the "
                            + "generic type argument is preserved in bytecode. Use: "
                            + "new TypeReference<MyType>() {}");
        }
        Type captured = parameterized.getActualTypeArguments()[0];
        this.typeCategory = determineTypeCategory(captured);
        this.type         = determineType(captured);
        this.cachedHash   = this.type.hashCode();
    }

    private TypeCategory determineTypeCategory(Type type) {
        return switch (type) {
            case Class<?> _                                                    -> TypeCategory.CLASS;
            case ParameterizedType _                                           -> TypeCategory.PARAMETERIZED_TYPE;
            case GenericArrayType _                                            -> TypeCategory.GENERIC_ARRAY_TYPE;
            case WildcardType _                                                -> TypeCategory.WILDCARD_TYPE;
            case TypeVariable<?> variable when variable.getBounds().length > 1 ->
                 TypeCategory.INTERSECTION_TYPE;
            case TypeVariable<?> _                                             -> TypeCategory.TYPE_VARIABLE;
            default                                                            -> TypeCategory.OTHER;
        };
    }

    private Type determineType(Type type) {
        TypeReferenceResolver resolver = TypeReferenceResolverFactory.getResolver(typeCategory);
        return resolver.resolve(type);
    }

    /**
     * Returns the captured {@link Type}.
     *
     * <p>The type is either the one passed to the {@linkplain #TypeReference(Type) explicit
     * constructor} or the one extracted from the super-type token via {@link
     * Class#getGenericSuperclass()}.
     *
     * @return the underlying {@link Type} (never {@code null})
     */
    public Type getType() {
        return type;
    }

    /**
     * Compares this {@code TypeReference} with another object for equality.
     *
     * <p>Two {@code TypeReference} instances are equal if they wrap equal {@link Type} objects,
     * regardless of whether they were created from different anonymous subclasses. A fast-path
     * identity check is applied first: most {@code TypeReference} instances over the same {@code
     * Class<?>} share the same {@code Class<?>} instance (JVM interning), so identity comparison
     * typically short-circuits before delegating to {@code Type.equals()}.
     *
     * @param o the object to compare with
     * @return {@code true} if both objects are {@code TypeReference} instances and wrap equal {@code
     *     Type} objects
     */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof TypeReference<?> other))
            return false;
        if (this.type == other.type)
            return true;
        return type.equals(other.type);
    }

    /**
     * Returns the cached hash code of the underlying {@link Type}.
     *
     * <p>The hash code is computed once in the constructor and cached in a {@code final} field. This
     * design avoids the per-call allocation of a varargs array that would be incurred by {@code
     * Objects.hash(type)} on every invocation. The cache is safe because the {@code cachedHash} field
     * is final and set at construction time, making it effectively immutable across all threads.
     *
     * @return the cached hash code (stable for the lifetime of this instance)
     */
    @Override
    public int hashCode() {
        // return the cached value computed at construction
        // time. Previously this recomputed Objects.hash(type) on every
        // call, which is O(1) but allocates a varargs array per
        // invocation. The registries call hashCode() once per dispatch.
        return cachedHash;
    }
}
