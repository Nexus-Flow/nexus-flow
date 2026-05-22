package net.nexus_flow.core.runtime;

import java.util.Map;

/**
 * Singleton "no authenticated user" implementation of {@link SecurityPrincipal}. Returned by {@link
 * SecurityPrincipal#anonymous()}.
 *
 * <p>Compares equal to itself by identity ({@code ==} is safe and free). The {@link #name()} is the
 * constant string {@code "anonymous"}; {@link #attributes()} is empty.
 *
 * <p>Use this principal when:
 *
 * <ul>
 * <li>The dispatch is unauthenticated (public endpoint, health check).
 * <li>The dispatch is system-driven (outbox replay, scheduled-command worker, projection rebuild)
 * and no caller principal is meaningful.
 * <li>The dispatch ran before authentication has been performed (early-pipeline interceptor).
 * </ul>
 */
public final class AnonymousPrincipal implements SecurityPrincipal {

    /** Singleton instance. */
    public static final AnonymousPrincipal INSTANCE = new AnonymousPrincipal();

    private AnonymousPrincipal() {
    }

    @Override
    public String name() {
        return "anonymous";
    }

    @Override
    public Map<String, Object> attributes() {
        return Map.of();
    }

    @Override
    public String toString() {
        return "AnonymousPrincipal";
    }
}
