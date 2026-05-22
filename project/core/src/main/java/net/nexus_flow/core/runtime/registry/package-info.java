/**
 * Handler registry: discovery, lookup, and caching infrastructure for message handlers.
 *
 * <p>This package owns the contracts and machinery for registering and resolving handlers at
 * runtime:
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.runtime.registry.HandlerRegistry} — the central registry that
 * maps message types to ordered handler lists; backed by a copy-on-write {@link
 * net.nexus_flow.core.runtime.registry.RegistrationSnapshot} and an {@link
 * java.lang.ClassValue}-based cache invalidated on every registration.
 * <li>{@link net.nexus_flow.core.runtime.registry.HandlerInvoker} — the SPI contract for invoking
 * a single handler instance; all hot-path dispatch goes through this interface.
 * <li>{@link net.nexus_flow.core.runtime.registry.MethodHandleHandlerInvoker} — the default
 * implementation using pre-bound {@link java.lang.invoke.MethodHandle}s; effectively as fast
 * as a direct virtual call after JIT warm-up.
 * <li>{@link net.nexus_flow.core.runtime.registry.ReflectiveHandlerInvoker} — reflection-based
 * fallback for cases where {@code MethodHandle} lookup fails.
 * <li>{@link net.nexus_flow.core.runtime.registry.RegistrationSnapshot} — immutable snapshot of
 * all registrations at a point in time; used by the dispatcher and the cache layer.
 * </ul>
 *
 * <p>Thread-safety: all public operations on {@link
 * net.nexus_flow.core.runtime.registry.HandlerRegistry} are thread-safe. Reads (lookups) use the
 * lock-free {@code ClassValue} cache; writes (registrations) hold a write lock and replace the
 * entire snapshot atomically.
 */
@NullMarked
package net.nexus_flow.core.runtime.registry;

import org.jspecify.annotations.NullMarked;
