package net.nexus_flow.core.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * marker for classes whose state lives for the lifetime of a {@link FlowRuntime} instance and
 * <strong>must not</strong> be exposed through a process-wide singleton.
 *
 * <p>A class marked {@code @PerRuntime} guarantees, by contract, that:
 *
 * <ul>
 * <li>It carries no {@code public static getInstance()} accessor.
 * <li>It carries no {@code public static final ... INSTANCE} field referring to itself.
 * <li>Its mutable state (handler registries, executors, scope attributes) is owned by exactly one
 * {@link FlowRuntime} and is released when that runtime is {@linkplain FlowRuntime#close()
 * closed}.
 * </ul>
 *
 * <p>The {@code NoStaticGetInstanceTest} architecture test enforces this invariant by reflection —
 * adding a {@code getInstance()} or {@code INSTANCE} field to a {@code @PerRuntime} class fails the
 * build. The marker is intentionally empty: it only exists so the arch test can discriminate
 * "legitimate stateless markers" (e.g. {@link ErrorPolicy.FailFast#INSTANCE} on a value type) from
 * "runtime-scoped mutable state masquerading as a singleton".
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PerRuntime {
}
