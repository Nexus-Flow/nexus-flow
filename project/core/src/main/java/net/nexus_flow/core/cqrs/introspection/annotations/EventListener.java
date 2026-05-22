package net.nexus_flow.core.cqrs.introspection.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that the annotated method or type is a domain-event listener that adapter modules should
 * discover and register on the {@link net.nexus_flow.core.cqrs.event.EventBus}.
 *
 * <p>This annotation has no runtime behavior inside {@code core}; it is a portable discovery marker
 * consumed by integration layers.
 *
 * <p>{@link RetentionPolicy#RUNTIME RUNTIME} retention is intentional so runtime reflection users
 * and build-time indexers observe the same metadata.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface EventListener {
}
