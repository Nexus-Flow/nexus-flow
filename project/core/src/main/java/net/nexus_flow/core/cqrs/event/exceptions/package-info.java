/**
 * Public exception API surface of the event-bus pipeline.
 *
 * <p>carve-out aligning the event bus with the existing {@code cqrs.command.exceptions} and {@code
 * cqrs.query.exceptions} convention. Previously, the public event exceptions were intermingled with
 * the bus implementation in {@code cqrs.event}, while their command and query counterparts already
 * lived in dedicated {@code exceptions} sub-packages — a consistency gap that made the SPI surface
 * harder to discover for adapter authors and Javadoc readers.
 *
 * <p>Public API surface:
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.cqrs.event.exceptions.EventPublishRejectedException} — thrown by
 * the publish path when an {@link
 * net.nexus_flow.core.cqrs.event.EventPublishSaturationPolicy#REJECT} policy is configured
 * and the dispatch slot limit has been reached.
 * </ul>
 *
 * <p><strong>Internal note.</strong> {@code ListenerInvocationException} is intentionally
 * <em>not</em> part of this package: it is a package-private carrier that never escapes the
 * event-bus dispatch path. Promoting it to this public API surface would expose an implementation
 * detail that callers must not catch directly.
 */
@org.jspecify.annotations.NullMarked
package net.nexus_flow.core.cqrs.event.exceptions;
