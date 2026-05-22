package net.nexus_flow.core.runtime;

import java.util.Map;
import java.util.Objects;

/**
 * Lightweight {@link SecurityPrincipal} carrying just a name and an attribute bag — the common
 * shape returned by {@link SecurityPrincipal#named(String)} and {@link
 * SecurityPrincipal#named(String, Map)}.
 *
 * <p>Adapter modules that wrap a richer authentication object (Spring's {@code Authentication},
 * Quarkus {@code SecurityIdentity}, JWT subject + claims) ship their own implementations of {@link
 * SecurityPrincipal} instead of using this type. {@code NamedPrincipal} is the "I just have a name"
 * fallback.
 *
 * <p>{@code attributes} is defensively copied at construction so subsequent mutation of the
 * supplied map does not leak in. The principal is value-equal — two {@code NamedPrincipal}
 * instances with the same name and attributes compare equal.
 *
 * @param name       the principal name; must not be {@code null} or blank
 * @param attributes adapter-specific claims; must not be {@code null}; defensively copied
 */
public record NamedPrincipal(String name, Map<String, Object> attributes)
        implements SecurityPrincipal {

    public NamedPrincipal {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("NamedPrincipal name must not be blank");
        }
        Objects.requireNonNull(attributes, "attributes");
        attributes = Map.copyOf(attributes);
    }
}
