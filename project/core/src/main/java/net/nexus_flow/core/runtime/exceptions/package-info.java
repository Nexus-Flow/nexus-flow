/**
 * Typed exceptions for runtime SDK misuse.
 *
 * <p>All exceptions here extend {@link java.lang.IllegalStateException} so callers that catch the
 * idiomatic Java type keep working; callers that want to distinguish specific cases (e.g., closed
 * runtime vs missing configuration) can catch the typed subclass.
 *
 * <p><b>Convention.</b> The library uses idiomatic Java exceptions ({@code
 * IllegalArgumentException}, {@code IllegalStateException}, {@code UnsupportedOperationException})
 * for SDK-misuse and input validation, and custom typed exceptions (extending {@code DomainError}
 * or the appropriate built-in) when callers must discriminate to handle differently (retry vs
 * reject, compensate vs fail, integrate with framework exception handlers). Wholesale replacement
 * of every built-in throw with a custom type would break ecosystem integration (Spring's {@code
 * ResponseStatusException} resolvers, Quarkus exception mappers, etc.) and is intentionally
 * avoided.
 */
package net.nexus_flow.core.runtime.exceptions;
