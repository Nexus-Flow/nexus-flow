package net.nexus_flow.core.cqrs.reflection;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.Objects;

public class TypeReferenceVariable<D extends GenericDeclaration> implements TypeVariable<D> {
    private final String name;
    private final D genericDeclaration;
    private final Type[] bounds;
    private final AnnotatedType[] annotatedBounds;

    public TypeReferenceVariable(String name, D genericDeclaration, Type[] bounds, AnnotatedType[] annotatedBounds) {
        this.name = name;
        this.genericDeclaration = genericDeclaration;
        this.bounds = bounds;
        this.annotatedBounds = annotatedBounds;
    }

    @Override
    public Type[] getBounds() {
        return bounds;
    }

    @Override
    public D getGenericDeclaration() {
        return genericDeclaration;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public AnnotatedType[] getAnnotatedBounds() {
        return annotatedBounds;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TypeReferenceVariable<?> typeReferenceVariable = (TypeReferenceVariable<?>) o;
        return Objects.equals(name, typeReferenceVariable.name) &&
                Arrays.equals(bounds, typeReferenceVariable.bounds);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(name);
        result = 31 * result + Arrays.hashCode(bounds);
        return result;
    }

    @Override
    public <T extends Annotation> T getAnnotation(Class<T> annotationClass) {
        for (AnnotatedType annotatedType : annotatedBounds) {
            T annotation = annotatedType.getAnnotation(annotationClass);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    @Override
    public Annotation[] getAnnotations() {
        return Arrays.stream(annotatedBounds)
                .flatMap(annotatedType -> Arrays.stream(annotatedType.getAnnotations()))
                .toArray(Annotation[]::new);
    }

    @Override
    public Annotation[] getDeclaredAnnotations() {
        return getAnnotations();
    }
}