package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * type-level contract for {@link ErrorPolicy}.
 *
 * <p>Behaviour wiring (FailFast cancels siblings, CollectFailures aggregates, IsolatePerBoundary
 * confines to a sub-scope) lives in ; this slice only proves the value type is sound.
 */
class ErrorPolicyTest {

    @Test
    void failFast_isASingleton() {
        assertSame(ErrorPolicy.failFast(), ErrorPolicy.failFast());
        assertNotNull(ErrorPolicy.failFast());
        assertInstanceOf(ErrorPolicy.FailFast.class, ErrorPolicy.failFast());
    }

    @Test
    void collectFailures_isASingleton() {
        assertSame(ErrorPolicy.collectFailures(), ErrorPolicy.collectFailures());
        assertNotNull(ErrorPolicy.collectFailures());
        assertInstanceOf(ErrorPolicy.CollectFailures.class, ErrorPolicy.collectFailures());
    }

    @Test
    void ignore_storesAndExposesPredicate() {
        Predicate<Throwable>       p      = t -> t instanceof IllegalArgumentException;
        ErrorPolicy.IgnoreFailures policy = ErrorPolicy.ignore(p);

        assertSame(p, policy.predicate());
        assertTrue(policy.predicate().test(new IllegalArgumentException("x")));
        assertFalse(policy.predicate().test(new RuntimeException("x")));
    }

    @Test
    void ignore_rejectsNullPredicate() {
        assertThrows(NullPointerException.class, () -> ErrorPolicy.ignore(null));
    }

    @Test
    void isolate_wrapsInner() {
        ErrorPolicy.IsolatePerBoundary boundary = ErrorPolicy.isolate(ErrorPolicy.collectFailures());

        assertSame(ErrorPolicy.collectFailures(), boundary.inner());
    }

    @Test
    void isolate_rejectsNullInner() {
        assertThrows(NullPointerException.class, () -> ErrorPolicy.isolate(null));
    }

    @Test
    void isolate_rejectsNestedIsolation() {
        // A boundary is a one-level scope confiner. Nesting
        // would silently flatten the topology, so we surface the
        // misconfiguration at construction time.
        ErrorPolicy.IsolatePerBoundary first = ErrorPolicy.isolate(ErrorPolicy.failFast());

        assertThrows(IllegalArgumentException.class, () -> ErrorPolicy.isolate(first));
    }

    @Test
    void switchExpression_overErrorPolicy_isExhaustive() {
        // No default branch — adding a new permits subtype must break
        // this test until every case is handled explicitly.
        ErrorPolicy[] samples = {ErrorPolicy.failFast(), ErrorPolicy.collectFailures(), ErrorPolicy.ignore(t -> false), ErrorPolicy.isolate(
                                                                                                                                            ErrorPolicy
                                                                                                                                                    .failFast())
        };

        String[] tags = new String[samples.length];
        for (int i = 0; i < samples.length; i++) {
            tags[i] =
                    switch (samples[i]) {
                        case ErrorPolicy.FailFast _     -> "fail-fast";
                        case ErrorPolicy.CollectFailures _ -> "collect";
                        case ErrorPolicy.IgnoreFailures _ -> "ignore";
                        case ErrorPolicy.IsolatePerBoundary iso -> "isolate(" + iso.inner() + ")";
                    };
        }

        assertEquals("fail-fast", tags[0]);
        assertEquals("collect", tags[1]);
        assertEquals("ignore", tags[2]);
        assertTrue(tags[3].startsWith("isolate("));
    }
}
