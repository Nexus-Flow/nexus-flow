package net.nexus_flow.core.types;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.TypeVariable;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link TypeReference#hashCode()} is computed once in the constructor and cached
 * thereafter (not recomputed on each call to avoid allocating varargs arrays).
 */
class TypeReferenceHashCodeIsCachedTest {

    static final class Left<T> {
    }

    static final class Right<T> {
    }

    static final class Intersections<T extends Runnable & Serializable> {
    }

    @Test
    void hashCode_cached_neverRecomputed() throws Exception {
        TypeReference<String> ref = new TypeReference<>(String.class);

        Field cached = TypeReference.class.getDeclaredField("cachedHash");
        cached.setAccessible(true);
        int stamped = cached.getInt(ref);

        // The cached field must already match the publicly observable hash.
        assertEquals(stamped, ref.hashCode());

        // Hammer hashCode() many times. The cached field is final and
        // therefore cannot change; we still re-read it to assert no
        // observer ever sees a different value.
        int previous = ref.hashCode();
        for (int i = 0; i < 1_000_000; i++) {
            int next = ref.hashCode();
            assertEquals(previous, next, "hashCode must be stable across invocations");
            previous = next;
        }

        // Underlying field never mutated.
        assertEquals(stamped, cached.getInt(ref));
    }

    @Test
    void hashCode_matches_underlyingType() {
        TypeReference<Integer> ref = new TypeReference<>(Integer.class);
        assertEquals(Integer.class.hashCode(), ref.hashCode());
    }

    @Test
    void equals_fastPath_identity_onSameClass() {
        // Two TypeReferences from the same Class instance compare equal via identity short-circuit
        TypeReference<Long> a = new TypeReference<>(Long.class);
        TypeReference<Long> b = new TypeReference<>(Long.class);
        assertEquals(a, b);
        assertEquals(b, a);
        assertEquals(a.hashCode(), b.hashCode());
        // The underlying Class object is identity-shared by the JVM.
        assertSame(a.getType(), b.getType());
        // Sanity: reflexive equality.
        assertEquals(a, a);
    }

    @Test
    void equals_usesCapturedType_evenAcrossDifferentAnonymousSubclasses() {
        TypeReference<List<String>> a = listOfStringsRefA();
        TypeReference<List<String>> b = listOfStringsRefB();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void typeVariables_fromDifferentDeclarations_doNotCollide() {
        TypeVariable<?> leftVariable  = Left.class.getTypeParameters()[0];
        TypeVariable<?> rightVariable = Right.class.getTypeParameters()[0];

        TypeReferenceVariable<?> left  =
                (TypeReferenceVariable<?>) new TypeReference<>(leftVariable).getType();
        TypeReferenceVariable<?> right =
                (TypeReferenceVariable<?>) new TypeReference<>(rightVariable).getType();

        assertNotEquals(left, right);
        assertNotEquals(left.hashCode(), right.hashCode());
    }

    @Test
    void intersectionBounds_areWrapped_asTypeReferenceVariables() {
        TypeVariable<?> intersection = Intersections.class.getTypeParameters()[0];

        assertInstanceOf(TypeReferenceVariable.class, new TypeReference<>(intersection).getType());
    }

    private static TypeReference<List<String>> listOfStringsRefA() {
        return new TypeReference<List<String>>() {
        };
    }

    private static TypeReference<List<String>> listOfStringsRefB() {
        return new TypeReference<List<String>>() {
        };
    }
}
