/**
 * Built-in {@link net.nexus_flow.core.runtime.dispatch.DispatchInterceptor} implementations shipped
 * with the core module.
 *
 * <p>These interceptors are wired into {@link net.nexus_flow.core.runtime.FlowRuntime} by default
 * or can be added manually via {@link
 * net.nexus_flow.core.runtime.FlowRuntime.Builder#interceptor(net.nexus_flow.core.runtime.dispatch.DispatchInterceptor)}:
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.runtime.interceptors.LoggingDispatchInterceptor} — logs the
 * message type, execution context, and elapsed time for every dispatch using {@link
 * System.Logger}. Configurable at any {@link System.Logger.Level}; designed as the reference
 * reference implementation for OTel/Micrometer adapter bridges.
 * </ul>
 *
 * <p>{@code DispatchInterceptor} is intentionally not sealed so adapter modules (Spring, Quarkus,
 * reactive) can provide their own implementations without a compile-time dependency on this
 * package. This package merely contains the reference implementations and should not be treated as
 * exhaustive.
 */
@NullMarked
package net.nexus_flow.core.runtime.interceptors;

import org.jspecify.annotations.NullMarked;
