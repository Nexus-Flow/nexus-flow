package net.nexus_flow.core.cqrs.introspection.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that the annotated method or type is a command handler that adapter modules should
 * discover and register on the {@link net.nexus_flow.core.cqrs.command.CommandBus}.
 *
 * <p><strong>This annotation has no runtime semantics in {@code core}.</strong> It exists so every
 * IoC integration (Spring, Quarkus, Micronaut, Guice, CDI, and similar frameworks) shares one
 * portable marker, allowing applications compiled against {@code core} to move between containers
 * without source changes.
 *
 * <p>{@link RetentionPolicy#RUNTIME RUNTIME} retention is intentional: Spring and plain reflection
 * integrations inspect it at runtime, while Quarkus and Micronaut can still read the class-file
 * metadata during build-time indexing or annotation processing.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface CommandHandler {
}
