/**
 * portable marker annotations for IoC adapter modules.
 *
 * <h2>Why these live in {@code core}</h2>
 *
 * Every IoC container (Spring, Quarkus, Micronaut, Guice, CDI, …) eventually needs <em>some</em>
 * marker to recognise framework handlers among the application's beans. If {@code core} did not
 * ship the markers, each adapter would invent its own ({@code @SpringCommandHandler},
 * {@code @QuarkusCommandHandler}, …) and applications could not be ported between containers
 * without source changes.
 *
 * <p>The convention follows {@code jakarta.persistence.Entity}: the marker is part of the abstract
 * framework; the implementation lives in adapter modules.
 *
 * <h2>No runtime semantics</h2>
 *
 * {@code core} never reflects on these annotations. They are purely portable hints that adapter
 * modules read with {@code ApplicationContext.getBeansWithAnnotation}, Quarkus build-step
 * processors, Micronaut annotation processors, etc., and translate into {@link
 * net.nexus_flow.core.cqrs.introspection.CommandHandlerRegistration} / {@link
 * net.nexus_flow.core.cqrs.introspection.QueryHandlerRegistration} / {@link
 * net.nexus_flow.core.cqrs.introspection.EventListenerRegistration} via the existing {@code
 * fromMethod(...)} bridges.
 */
@org.jspecify.annotations.NullMarked
package net.nexus_flow.core.cqrs.introspection.annotations;
