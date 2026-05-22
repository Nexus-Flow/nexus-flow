/**
 * Handler-method introspection for the three CQRS buses.
 *
 * <p>rename: this package used to be called {@code cqrs.reflection}, which was misleading because
 * half of its inhabitants (the {@code TypeReference} family) were not reflection-specific — they
 * were cross-cutting type tokens used by outbox, inbox, and event sourcing. Those have been hoisted
 * to {@link net.nexus_flow.core.types}; what remains here is <em>strictly</em> handler-method
 * introspection: the contract used by the framework integrators (Spring / Quarkus / Micronaut
 * adapter modules) to walk a candidate bean, identify annotated handler methods, and produce
 * immutable {@code *Registration} records that can be fed back into the corresponding bus's {@code
 * register} overload without reopening any sealed hierarchy.
 *
 * <p>The introspection API is intentionally framework-agnostic: it neither depends on Spring nor
 * Jakarta CDI annotations, nor does it scan the classpath itself. Adapter modules provide the
 * discovery step; this package only provides the metadata schema.
 *
 * <p>Public API surface:
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.cqrs.introspection.HandlerMethodIntrospector} — pure-function
 * entry point that turns a candidate method + owner instance into the appropriate {@code
 *       *Registration} record.
 * <li>{@link net.nexus_flow.core.cqrs.introspection.CommandHandlerRegistration}, {@link
 * net.nexus_flow.core.cqrs.introspection.EventListenerRegistration}, {@link
 * net.nexus_flow.core.cqrs.introspection.QueryHandlerRegistration} — immutable records
 * carrying everything the corresponding bus needs to register the handler without further
 * reflection.
 * </ul>
 */
@org.jspecify.annotations.NullMarked
package net.nexus_flow.core.cqrs.introspection;
