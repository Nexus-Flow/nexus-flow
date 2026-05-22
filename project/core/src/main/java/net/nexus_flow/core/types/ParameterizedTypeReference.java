package net.nexus_flow.core.types;

import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Objects;
import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/**
 * Reference implementation of {@link ParameterizedType} for capturing parameterised generics.
 *
 * <p>This class wraps a raw type and its type arguments, implementing the {@link ParameterizedType}
 * contract. It is typically constructed by {@link TypeReferenceResolverFactory} when resolving a
 * parameterised type encountered during type introspection.
 *
 * <p>Instances are effectively immutable and {@link Serializable}. Type tokens are process-scoped
 * (they depend on runtime {@link java.lang.reflect.Type} objects that cannot be persisted);
 * serialisation support exists only for in-process caching in maps and collections.
 *
 * <p>Equality and hashing are based on the raw type and type arguments, allowing instances to be
 * used as map keys in type registries.
 *
 * @see TypeReference
 * @see ParameterizedType
 */
public final class ParameterizedTypeReference implements ParameterizedType, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Reflection {@link Type} token representing the raw type.
     *
     * <p>Serialisation is supported only when the underlying {@link Type} implementation is itself
     * serializable (for example {@link Class} or another serializable reference wrapper).
     */
    @SuppressWarnings({"serial", // javac -Xlint:serial: Type serializability depends on the runtime Type
            // implementation.
            "java:S1948" // Sonar: same conditional-serialization contract.
    })
    private final Type rawType;

    @SuppressWarnings({"serial", // javac -Xlint:serial: Type serializability depends on the runtime Type
            // implementation.
            "java:S1948" // Sonar: same conditional-serialization contract.
    })
    private final Type[] typeArguments;

    /**
     * Constructs a new {@code ParameterizedTypeReference} with a raw type and type arguments.
     *
     * <p>The provided type arguments are copied to ensure the instance remains effectively immutable;
     * mutations to the input array do not affect this instance.
     *
     * @param rawType       the raw type (e.g., {@code List.class} for {@code List<String>}). Must not be
     *                      {@code null}.
     * @param typeArguments the actual type arguments (e.g., {@code [String.class]} for {@code
     *     List<String>} ). Must not be {@code null}; may be empty for non-parameterised types.
     * @throws NullPointerException if {@code rawType} or {@code typeArguments} is {@code null}
     */
    public ParameterizedTypeReference(Type rawType, Type[] typeArguments) {
        this.rawType       = Objects.requireNonNull(rawType, "rawType");
        this.typeArguments =
                Arrays.copyOf(Objects.requireNonNull(typeArguments, "typeArguments"), typeArguments.length);
    }

    /**
     * Returns the string representation of this parameterized type.
     *
     * <p>For non-parameterized types, returns the raw type's name. For parameterized types, appends
     * type arguments in angle brackets, e.g., {@code java.util.List<java.lang.String>}.
     *
     * @return the string representation
     * @see Type#getTypeName()
     */
    @Override
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

    /**
     * Returns the owner type of this parameterised type.
     *
     * <p>This implementation always returns {@code null} because top-level types (such as {@code
     * List<String>}) have no owner. Nested parameterised types (such as {@code Outer<T>.Inner<U>})
     * would report their outer type here.
     *
     * @return {@code null} (no owner type for top-level parameterisations)
     */
    @Override
    public @Nullable Type getOwnerType() {
        return null;
    }

    /**
     * Returns the raw type of this parameterisation.
     *
     * <p>For {@code List<String>}, this returns {@code List.class}. For {@code Map<String, Integer>},
     * this returns {@code Map.class}.
     *
     * @return the raw type (never {@code null})
     */
    @Override
    public Type getRawType() {
        return this.rawType;
    }

    /**
     * Returns the actual type arguments.
     *
     * <p>For {@code List<String>}, this returns {@code [String.class]}. For {@code Map<String,
     * Integer>}, this returns {@code [String.class, Integer.class]}. The returned array is a copy and
     * may be freely modified without affecting this instance.
     *
     * @return a copy of the type arguments array (may be empty for non-parameterised types)
     */
    @Override
    public Type[] getActualTypeArguments() {
        return Arrays.copyOf(this.typeArguments, this.typeArguments.length);
    }

    /**
     * Non-defensive view of the actual type arguments — returns the internal array WITHOUT a
     * copy. The caller MUST treat the array as immutable; mutating it will corrupt this
     * instance for every future reader.
     *
     * <p>Intended for the framework's hot reflection-resolution paths where the caller is
     * known to never mutate the returned array. Library users that need the JDK
     * {@link ParameterizedType} contract use {@link #getActualTypeArguments()}.
     *
     * @return the internal type-argument array; never {@code null}
     */
    @SuppressWarnings("PMD.MethodReturnsInternalArray")
    public Type[] actualTypeArgumentsUnsafe() {
        return typeArguments;
    }

    /**
     * Compares this parameterised type reference with another object.
     *
     * <p>Two {@code ParameterizedTypeReference} instances are equal if they have equal owner types,
     * equal raw types, and equal type argument arrays (compared element-wise). This follows the
     * {@link ParameterizedType} contract.
     *
     * @param other the object to compare with
     * @return {@code true} if both are {@code ParameterizedType} instances with equal raw types and
     *         type arguments
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other instanceof ParameterizedType that) {
            return Objects.equals(getOwnerType(), that.getOwnerType()) && this.rawType.equals(that.getRawType()) && Arrays.equals(
                                                                                                                                  this.typeArguments,
                                                                                                                                  that.getActualTypeArguments());
        }
        return false;
    }

    /**
     * Returns the hash code of this parameterised type reference.
     *
     * <p>The hash code is computed from the owner type, raw type, and type arguments, allowing
     * instances to be used as map keys in type registries while matching the {@link
     * ParameterizedType} equality contract.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        int result = Objects.hashCode(getOwnerType());
        result = 31 * result + this.rawType.hashCode();
        result = 31 * result + Arrays.hashCode(this.typeArguments);
        return result;
    }

    /**
     * Returns the string representation of this parameterised type.
     *
     * <p>This is a convenience method that delegates to {@link #getTypeName()}.
     *
     * @return the string representation
     */
    @Override
    public String toString() {
        return this.getTypeName();
    }
}
