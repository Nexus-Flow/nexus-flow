package net.nexus_flow.core.types;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Reference implementation of {@link TypeVariable} for capturing free type variables.
 *
 * <p>This class wraps a type variable (such as the {@code T} in {@code class Foo<T>}) and provides
 * a concrete {@link TypeVariable} implementation. It is typically constructed by {@link
 * TypeReferenceResolverFactory} when resolving a type variable encountered during type
 * introspection.
 *
 * <p>Instances are effectively immutable and safe for use as map keys in type registries. Equality
 * is based on the variable name, its declaring element, and its bounds so unrelated declarations do
 * not collapse onto the same value.
 *
 * @param <D> the type of generic declaration (e.g., {@code Class}, {@code Method})
 * @see TypeVariable
 * @see TypeReference
 */
public class TypeReferenceVariable<D extends GenericDeclaration> implements TypeVariable<D> {
    private final String          name;
    private final D               genericDeclaration;
    private final Type[]          bounds;
    private final AnnotatedType[] annotatedBounds;

    /**
     * Constructs a new {@code TypeReferenceVariable} from a type variable.
     *
     * <p>The provided bounds and annotated bounds arrays are copied to ensure the instance remains
     * effectively immutable.
     *
     * @param name               the name of the type variable (e.g., {@code "T"})
     * @param genericDeclaration the declaration that introduced this type variable (e.g., a {@code
     *     Class}             , {@code Method}, or {@code Constructor})
     * @param bounds             the upper bounds of the type variable (e.g., {@code [Number.class,
     *     Comparable.class]} ); may be empty to indicate no explicit bounds
     * @param annotatedBounds    the annotated bounds of the type variable; may be empty
     * @throws NullPointerException if any argument is {@code null}
     */
    public TypeReferenceVariable(
            String name, D genericDeclaration, Type[] bounds, AnnotatedType[] annotatedBounds) {
        this.name               = Objects.requireNonNull(name, "name");
        this.genericDeclaration = Objects.requireNonNull(genericDeclaration, "genericDeclaration");
        this.bounds             = Arrays.copyOf(Objects.requireNonNull(bounds, "bounds"), bounds.length);
        this.annotatedBounds    =
                Arrays.copyOf(
                              Objects.requireNonNull(annotatedBounds, "annotatedBounds"), annotatedBounds.length);
    }

    /**
     * Returns a copy of the upper bounds of this type variable.
     *
     * <p>For an unbounded type variable (e.g., {@code <T>}), this returns an array containing only
     * {@code Object.class}. For a bounded type variable (e.g., {@code <T extends Number>}), this
     * returns the declared bounds.
     *
     * @return a copy of the bounds array (never {@code null})
     */
    @Override
    public Type[] getBounds() {
        return Arrays.copyOf(bounds, bounds.length);
    }

    /**
     * Returns the declaration that introduced this type variable.
     *
     * <p>This is typically a {@code Class}, {@code Method}, or {@code Constructor}.
     *
     * @return the generic declaration (never {@code null})
     */
    @Override
    public D getGenericDeclaration() {
        return genericDeclaration;
    }

    /**
     * Returns the name of this type variable.
     *
     * <p>For example, {@code "T"} for a type variable {@code T}.
     *
     * @return the name (never {@code null})
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Returns a copy of the annotated bounds of this type variable.
     *
     * <p>Annotations on the bounds themselves are preserved in the returned {@link AnnotatedType}
     * objects.
     *
     * @return a copy of the annotated bounds array (never {@code null})
     */
    @Override
    public AnnotatedType[] getAnnotatedBounds() {
        return Arrays.copyOf(annotatedBounds, annotatedBounds.length);
    }

    /**
     * Compares this type variable with another object.
     *
     * <p>Two {@code TypeReferenceVariable} instances are equal if they have the same name, declaring
     * element, and equal bounds.
     *
     * @param o the object to compare with
     * @return {@code true} if both are {@code TypeReferenceVariable} instances with equal names,
     *         declarations, and bounds
     */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof TypeReferenceVariable<?> typeReferenceVariable))
            return false;
        return Objects.equals(name, typeReferenceVariable.name) && Objects.equals(genericDeclaration,
                                                                                  typeReferenceVariable.genericDeclaration) && Arrays
                                                                                          .equals(bounds, typeReferenceVariable.bounds);
    }

    /**
     * Returns the hash code of this type variable.
     *
     * <p>The hash code is computed from the name, declaring element, and bounds, allowing instances
     * to be used as map keys in type registries without collisions across unrelated declarations.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        int result = Objects.hash(name, genericDeclaration);
        result = 31 * result + Arrays.hashCode(bounds);
        return result;
    }

    /**
     * Returns the annotation of the specified type on one of the annotated bounds.
     *
     * <p>This method searches through the annotated bounds and returns the first annotation of the
     * specified type found. If no such annotation is found, {@code null} is returned.
     *
     * @param <T>             the annotation type
     * @param annotationClass the annotation class to search for
     * @return the annotation instance if found on one of the bounds, {@code null} otherwise
     */
    @Override
    public <T extends Annotation> @Nullable T getAnnotation(Class<T> annotationClass) {
        for (AnnotatedType annotatedType : annotatedBounds) {
            T annotation = annotatedType.getAnnotation(annotationClass);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    /**
     * Returns all annotations on all of the annotated bounds.
     *
     * <p>This is a convenience method that flattens annotations from all bounds into a single array.
     *
     * @return an array of all annotations on the annotated bounds (never {@code null})
     */
    @Override
    public Annotation[] getAnnotations() {
        return Arrays.stream(annotatedBounds)
                .flatMap(annotatedType -> Arrays.stream(annotatedType.getAnnotations()))
                .toArray(Annotation[]::new);
    }

    /**
     * Returns all declared annotations on all of the annotated bounds.
     *
     * <p>This implementation delegates to {@link #getAnnotations()}.
     *
     * @return an array of all declared annotations (never {@code null})
     */
    @Override
    public Annotation[] getDeclaredAnnotations() {
        return getAnnotations();
    }
}
