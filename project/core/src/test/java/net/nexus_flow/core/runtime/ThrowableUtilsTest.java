package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link ThrowableUtils#withSuppressed(Throwable, Throwable)} attachment and nullability
 * contract.
 */
class ThrowableUtilsTest {

    @Test
    void withSuppressedAttachesSuppressedExceptionAndReturnsIt() {
        // Why: Verify that withSuppressed attaches the suppressed exception to the primary
        // and returns the primary itself for chaining.
        Throwable primary    = new IllegalStateException("primary");
        Throwable suppressed = new RuntimeException("suppressed");

        Throwable result = ThrowableUtils.withSuppressed(primary, suppressed);

        assertSame(primary, result, "withSuppressed must return the primary exception");
        assertEquals(1, primary.getSuppressed().length, "primary must have one suppressed exception");
        assertSame(suppressed, primary.getSuppressed()[0], "suppressed must be the added exception");
    }

    @Test
    void withSuppressedIgnoresNullSuppressed() {
        // Why: If suppressed is null, withSuppressed should not add it, returning primary unchanged.
        Throwable primary = new IllegalStateException("primary");

        Throwable result = ThrowableUtils.withSuppressed(primary, null);

        assertSame(primary, result, "withSuppressed must return primary when suppressed is null");
        assertEquals(0, primary.getSuppressed().length, "primary must have no suppressed exceptions");
    }

    @Test
    void withSuppressedIgnoresSelfReference() {
        // Why: If primary and suppressed are the same object, don't add self-reference.
        Throwable primary = new IllegalStateException("primary");

        Throwable result = ThrowableUtils.withSuppressed(primary, primary);

        assertSame(primary, result, "withSuppressed must return primary");
        assertEquals(0, primary.getSuppressed().length, "self-reference must not be added");
    }

    @Test
    void withSuppressedAppendsWhenAlreadySuppressed() {
        // Why: If the primary already has suppressed exceptions, new suppression appends.
        Throwable primary       = new IllegalStateException("primary");
        Throwable existing      = new RuntimeException("existing");
        Throwable newSuppressed = new RuntimeException("new");

        primary.addSuppressed(existing);
        ThrowableUtils.withSuppressed(primary, newSuppressed);

        assertEquals(2, primary.getSuppressed().length, "both suppressions must be present");
        assertSame(existing, primary.getSuppressed()[0], "existing suppression must be first");
        assertSame(newSuppressed, primary.getSuppressed()[1], "new suppression must be second");
    }

    @Test
    void withSuppressedReturnsPrimaryForChaining() {
        // Why: Verify chaining: result of withSuppressed can be used as new primary.
        Throwable first  = new IllegalStateException("first");
        Throwable second = new RuntimeException("second");
        Throwable third  = new RuntimeException("third");

        ThrowableUtils.withSuppressed(first, second);
        ThrowableUtils.withSuppressed(first, third);

        assertEquals(2, first.getSuppressed().length, "chaining must append both suppressions");
        assertTrue(
                   (first.getSuppressed()[0] == second && first.getSuppressed()[1] == third) || (first.getSuppressed()[0] == third && first
                           .getSuppressed()[1] == second),
                   "both suppressions must be attached");
    }
}
