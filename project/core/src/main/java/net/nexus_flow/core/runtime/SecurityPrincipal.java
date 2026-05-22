package net.nexus_flow.core.runtime;

import java.util.Map;
import java.util.Objects;

/**
 * Authenticated principal propagated through every dispatch via {@link
 * ExecutionContext#principal()}.
 *
 * <p>This is the framework's SPI for "who initiated this dispatch?" — the same answer that
 * application security stacks (Spring Security, Quarkus Security, Micronaut Security, custom
 * JWT/OAuth pipelines) need to surface to authorization interceptors, audit handlers, and
 * per-tenant rate limiters. The interface stays deliberately minimal so adapter modules can map
 * their native principal types onto it without a lossy conversion:
 *
 * <ul>
 * <li>{@link #name()} — the stable user-facing identifier (username, subject claim, email).
 * <li>{@link #attributes()} — read-only metadata bag for adapter-specific claims (roles, scopes,
 * tenant memberships, JWT custom claims). Adapters that need richer semantics expose them
 * through subtype-specific accessors.
 * </ul>
 *
 * <p><strong>NOT sealed.</strong> Adapter modules (Spring, Quarkus, Micronaut, OAuth, JWT, …) MUST
 * be able to ship their own {@code SecurityPrincipal} implementation without re-opening {@code
 * core}. The contract above is the lowest common denominator; richer interfaces (e.g. a {@code
 * RolesPrincipal extends SecurityPrincipal} with a {@code Set<String> roles()}) are an additive
 * extension that adapter modules introduce as they need them.
 *
 * <p><strong>Anonymous default.</strong> Use {@link #anonymous()} when no authentication has been
 * performed yet (public endpoint, async outbox replay before the principal has been re-hydrated,
 * scheduled-command worker dispatching a system-owned command). {@link AnonymousPrincipal} is a
 * singleton — comparing with {@code ==} is safe and free.
 *
 * <p><strong>Equality.</strong> The default implementations ({@link AnonymousPrincipal}, {@link
 * NamedPrincipal}) define value equality. Adapter implementations are free to choose identity-based
 * equality if they wrap a mutable authentication object.
 *
 * <p>Persistence note: principals are NOT persisted by the outbox today. A scheduled-command or
 * outbox-row that survives a JVM restart is re-dispatched with a fresh {@link AnonymousPrincipal}
 * by default. Adapter modules that need durable principal propagation introduce their own
 * serialiser SPI in their module.
 */
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface SecurityPrincipal {

    /**
     * The stable user-facing identifier of this principal — username, subject claim, email,
     * service-account name. Never {@code null}; {@link AnonymousPrincipal} returns the constant
     * string {@code "anonymous"}.
     *
     * @return the principal's name; never {@code null}
     */
    String name();

    /**
     * Read-only attribute map carrying adapter-specific claims (roles, scopes, tenant memberships,
     * JWT custom claims). Default: empty map.
     *
     * @return an unmodifiable view of the principal's attributes; never {@code null}
     */
    default Map<String, Object> attributes() {
        return Map.of();
    }

    /**
     * Singleton "no authenticated user" principal. Returns the same instance on every call so
     * identity equality is safe.
     *
     * @return the anonymous principal singleton
     */
    static SecurityPrincipal anonymous() {
        return AnonymousPrincipal.INSTANCE;
    }

    /**
     * Convenience factory for the common "I just have a name and nothing else" case.
     *
     * @param name the principal name; must not be {@code null} or blank
     * @return a {@link NamedPrincipal} wrapping {@code name}
     */
    static SecurityPrincipal named(String name) {
        return new NamedPrincipal(name, Map.of());
    }

    /**
     * Convenience factory for "name + a few claims".
     *
     * @param name       the principal name; must not be {@code null} or blank
     * @param attributes adapter-specific claims; must not be {@code null}; defensively copied
     * @return a {@link NamedPrincipal} with the supplied claims
     */
    static SecurityPrincipal named(String name, Map<String, Object> attributes) {
        Objects.requireNonNull(attributes, "attributes");
        return new NamedPrincipal(name, attributes);
    }
}
