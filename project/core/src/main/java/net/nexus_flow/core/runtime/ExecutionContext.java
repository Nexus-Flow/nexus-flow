package net.nexus_flow.core.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.nexus_flow.core.runtime.ids.*;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import net.nexus_flow.core.runtime.result.FlowDeadlineExceededException;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, value-typed execution context propagated explicitly through every command/query/event
 * dispatch.
 *
 * <p>The context carries:
 *
 * <ul>
 * <li>{@link MessageId} — identity of <em>this</em> dispatch.
 * <li>{@link TraceId} — constant across the whole flow (technical).
 * <li>{@link CorrelationId} — constant across the whole conceptual operation.
 * <li>{@link CausationId} — id of the parent dispatch (root sentinel for top-level).
 * <li>{@link TenantId} — optional multi-tenant boundary identifier (nullable for single-tenant or
 * system-level dispatches). Propagated to outbox rows so re-dispatch on restart restores the
 * same scope.
 * <li>{@link SecurityPrincipal} — optional authenticated principal (nullable when the dispatch is
 * unauthenticated or system-driven). Not persisted by default; see {@link SecurityPrincipal}
 * for the contract.
 * <li>{@code deadline} — optional absolute deadline shared by parent and children.
 * <li>{@link CancellationToken} — cooperative cancellation primitive.
 * <li>{@code attributes} — read-only metadata map for observability/diagnostics.
 * </ul>
 *
 * <p>The context is immutable; nested dispatches obtain a derived instance through {@link
 * #childContextFor(MessageId)}. The child:
 *
 * <ul>
 * <li>keeps the same {@code traceId}, {@code correlationId}, {@code tenant}, {@code principal},
 * {@code deadline}, {@code cancellation} and {@code attributes};
 * <li>adopts a new {@code messageId};
 * <li>sets its {@code causationId} to the parent's {@code messageId}.
 * </ul>
 *
 * <p>{@link #throwIfCancelledOrExpired()} should be called at safe points by any code that
 * participates in cooperative cancellation / deadlines.
 *
 * <p><strong>Constructor ordering.</strong> {@code tenant} and {@code principal} appear between
 * {@code causationId} and {@code deadline} so that adapter modules and tests using the canonical
 * record constructor surface a positional argument list ordered by semantic category — identity (4
 * ids), security scope (tenant + principal), execution control (deadline + cancellation), and
 * metadata (attributes). Use the fluent builders ({@link #withTenant(TenantId)}, {@link
 * #withPrincipal(SecurityPrincipal)}, {@link #withAttribute(String, Object)}) if you would rather
 * not rely on positional arguments.
 */
public record ExecutionContext(
                               MessageId messageId,
                               TraceId traceId,
                               CorrelationId correlationId,
                               CausationId causationId,
                               @Nullable TenantId tenant,
                               @Nullable SecurityPrincipal principal,
                               @Nullable Instant deadline,
                               CancellationToken cancellation,
                               Map<String, Object> attributes) {

    public ExecutionContext {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(causationId, "causationId");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(attributes, "attributes");
        // Defensive immutable copy; deadline / tenant / principal are allowed to be null.
        attributes = Map.copyOf(attributes);
    }

    /**
     * Build a fresh root context: new ids, no deadline, ad-hoc cancellation token, empty attributes,
     * no tenant, no principal.
     *
     * @return a new root {@code ExecutionContext} with randomly generated {@link MessageId}, {@link
     *         TraceId}, and {@link CorrelationId}, no deadline, and a fresh mutable {@link
     *         CancellationToken}
     */
    public static ExecutionContext root() {
        return new ExecutionContext(
                MessageId.random(),
                TraceId.random(),
                CorrelationId.random(),
                CausationId.ROOT,
                null,
                null,
                null,
                CancellationToken.create(),
                Map.of());
    }

    /**
     * Build a fresh root context with a deadline computed against the system UTC clock.
     *
     * @param timeout duration from now until the deadline; must not be {@code null}
     * @return a root {@code ExecutionContext} whose {@link #deadline()} is set to {@code
     *     Instant.now(Clock.systemUTC()).plus(timeout)}
     * @throws NullPointerException if {@code timeout} is {@code null}
     */
    public static ExecutionContext rootWithTimeout(Duration timeout) {
        return rootWithTimeout(timeout, Clock.systemUTC());
    }

    /**
     * Test-friendly factory accepting an explicit clock.
     *
     * @param timeout duration from now (as given by {@code clock}) until the deadline; must not be
     *                {@code null}
     * @param clock   clock used to compute the deadline {@code Instant}; must not be {@code null}
     * @return a root {@code ExecutionContext} whose {@link #deadline()} equals {@code
     *     clock.instant().plus(timeout)}
     * @throws NullPointerException if either argument is {@code null}
     */
    public static ExecutionContext rootWithTimeout(Duration timeout, Clock clock) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(clock, "clock");
        return new ExecutionContext(
                MessageId.random(),
                TraceId.random(),
                CorrelationId.random(),
                CausationId.ROOT,
                null,
                null,
                clock.instant().plus(timeout),
                CancellationToken.create(),
                Map.of());
    }

    /**
     * Derive a child context for a nested dispatch identified by {@code childMessageId}.
     *
     * <p>The child preserves trace/correlation/tenant/principal/deadline/cancellation/attributes and
     * chains causation: {@code child.causationId == this.messageId}.
     *
     * @param childMessageId identity of the nested dispatch; must not be {@code null}
     * @return a new {@code ExecutionContext} sharing this context's {@link #traceId()}, {@link
     *         #correlationId()}, {@link #tenant()}, {@link #principal()}, {@link #deadline()}, {@link
     *         #cancellation()}, and {@link #attributes()}, with a new {@code messageId} and {@code
     *     causationId} equal to this context's {@code messageId}
     * @throws NullPointerException if {@code childMessageId} is {@code null}
     */
    public ExecutionContext childContextFor(MessageId childMessageId) {
        Objects.requireNonNull(childMessageId, "childMessageId");
        return new ExecutionContext(
                childMessageId,
                traceId,
                correlationId,
                messageId.asCausation(),
                tenant,
                principal,
                deadline,
                cancellation,
                attributes);
    }

    /**
     * Return a context identical to this one but with {@code key=value} added (or replaced) in the
     * attribute map. Attribute mutations are always copy-on-write.
     *
     * @param key   attribute key; must not be {@code null}
     * @param value attribute value; must not be {@code null}
     * @return a new {@code ExecutionContext} with the same ids, deadline, and cancellation token, but
     *         with the supplied key-value pair added or replaced in the attribute map
     * @throws NullPointerException if either {@code key} or {@code value} is {@code null}
     */
    public ExecutionContext withAttribute(String key, Object value) {
        Objects.requireNonNull(key, "key");
        Map<String, Object> next = new HashMap<>(attributes);
        next.put(key, value);
        return new ExecutionContext(
                messageId,
                traceId,
                correlationId,
                causationId,
                tenant,
                principal,
                deadline,
                cancellation,
                next);
    }

    /**
     * Return a context identical to this one but with the supplied tenant. Pass {@code null} to clear
     * any previously-set tenant.
     *
     * @param tenant the new tenant scope, or {@code null} to clear
     * @return a new {@code ExecutionContext} with the tenant replaced
     */
    public ExecutionContext withTenant(@Nullable TenantId tenant) {
        return new ExecutionContext(
                messageId,
                traceId,
                correlationId,
                causationId,
                tenant,
                principal,
                deadline,
                cancellation,
                attributes);
    }

    /**
     * Return a context identical to this one but with the supplied principal. Pass {@code null} to
     * clear any previously-set principal.
     *
     * @param principal the new authenticated principal, or {@code null} to clear
     * @return a new {@code ExecutionContext} with the principal replaced
     */
    public ExecutionContext withPrincipal(@Nullable SecurityPrincipal principal) {
        return new ExecutionContext(
                messageId,
                traceId,
                correlationId,
                causationId,
                tenant,
                principal,
                deadline,
                cancellation,
                attributes);
    }

    /** Convenience accessor that returns {@code true} when a deadline is set. */
    public boolean hasDeadline() {
        return deadline != null;
    }

    /** Convenience accessor that returns {@code true} when a tenant is bound. */
    public boolean hasTenant() {
        return tenant != null;
    }

    /** Convenience accessor that returns {@code true} when a principal is bound. */
    public boolean hasPrincipal() {
        return principal != null;
    }

    /**
     * Throw {@link FlowCancellationException} if the cancellation token has been triggered, or {@link
     * FlowDeadlineExceededException} if the deadline has elapsed (per the system UTC clock).
     */
    public void throwIfCancelledOrExpired() {
        throwIfCancelledOrExpired(Clock.systemUTC());
    }

    /**
     * Test-friendly variant that checks cancellation and deadline against the supplied {@code clock}.
     *
     * @param clock the clock used to determine whether the deadline has elapsed; must not be {@code
     *     null}
     * @throws net.nexus_flow.core.runtime.result.FlowCancellationException     if the cancellation token
     *                                                                          has been triggered
     * @throws net.nexus_flow.core.runtime.result.FlowDeadlineExceededException if a deadline is set
     *                                                                          and {@code clock.instant()} is at or after it
     * @throws NullPointerException                                             if {@code clock} is {@code null}
     */
    public void throwIfCancelledOrExpired(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        cancellation.throwIfCancellationRequested();
        if (deadline != null && !clock.instant().isBefore(deadline)) {
            throw new FlowDeadlineExceededException(deadline);
        }
    }
}
