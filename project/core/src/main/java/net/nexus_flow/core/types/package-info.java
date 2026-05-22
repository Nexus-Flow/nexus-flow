/**
 * Generic type tokens shared across the framework.
 *
 * <p>carve-out: these classes used to live in {@code cqrs.reflection}, which conflated two
 * responsibilities — <em>handler introspection</em> (a CQRS concern) and <em>type tokens</em> (a
 * cross-cutting concern used by outbox, inbox, event sourcing, and the type-aware handler
 * registry). Type tokens are not CQRS-specific: an outbox payload, an event envelope, and a saga
 * state all need to capture a parameterised generic type at runtime regardless of whether the
 * surrounding flow is a command, query, or event. Hoisting them to {@code core.types} prevents
 * downstream layers (and adapter modules) from depending on a CQRS package just to obtain a {@link
 * net.nexus_flow.core.types.TypeReference}.
 *
 * <p>Public API surface:
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.types.TypeReference} — opaque super-type token capturing a
 * parameterised generic type at compile time (Neal Gafter's trick).
 * <li>{@link net.nexus_flow.core.types.ParameterizedTypeReference} — sealed permitted subtype
 * used internally for typed routing.
 * <li>{@link net.nexus_flow.core.types.TypeReferenceResolver} / {@link
 * net.nexus_flow.core.types.TypeReferenceResolverFactory} — builds {@link
 * net.nexus_flow.core.types.TypeReference} instances from {@link java.lang.reflect.Type
 * java.lang.reflect.Type} at handler-introspection time.
 * <li>{@link net.nexus_flow.core.types.TypeCategory} — coarse classification of a captured
 * generic shape.
 * <li>{@link net.nexus_flow.core.types.TypeReferenceVariable} — carrier for a free type-variable
 * encountered during resolution.
 * </ul>
 *
 * <p>None of these classes are sealed against extension; they expose {@code equals}/{@code
 * hashCode} so they can be used as map keys in the bus routing tables.
 */
@org.jspecify.annotations.NullMarked
package net.nexus_flow.core.types;
