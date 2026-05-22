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
 * <h2>Why class, not record</h2>
 *
 * Was a {@code record} until JMH validated ({@code ExecutionContextValidationCostBenchmark}) that
 * the 6× {@code Objects.requireNonNull} in the compact constructor adds ~3.5 ns per allocation
 * vs an equivalent class with no validation. At 1 M dispatch/sec — the hyperscale envelope the
 * framework targets — that translates to ~3.5 ms/sec/core saved by skipping validation on
 * trusted internal allocation paths.
 *
 * <p>The class shape exposes:
 *
 * <ul>
 * <li>A public canonical constructor that validates every argument (the externally-callable
 * shape, matches the previous record canonical constructor semantically).
 * <li>A package-private {@link #unchecked} static factory that skips validation entirely. The
 * runtime's internal factories ({@link #root}, {@link #rootWithTimeout}, {@link
 * #childContextFor}, {@link #withAttribute}, {@link #withTenant}, {@link #withPrincipal}) use
 * the unchecked path because every argument is either a constant ({@link Map#of()}, {@link
 * CausationId#ROOT}) or sourced from another already-validated context.
 * </ul>
 *
 * <p>The public surface is preserved byte-for-byte: every accessor method has the same name and
 * signature the record auto-generated; {@link #equals(Object)} / {@link #hashCode()} / {@link
 * #toString()} are field-by-field, structurally identical to the record's auto-generated form.
 *
 * <p><strong>Constructor ordering.</strong> {@code tenant} and {@code principal} appear between
 * {@code causationId} and {@code deadline} so the canonical constructor surface lists positional
 * arguments by semantic category — identity (4 ids), security scope (tenant + principal),
 * execution control (deadline + cancellation), and metadata (attributes). Use the fluent
 * builders ({@link #withTenant(TenantId)}, {@link #withPrincipal(SecurityPrincipal)}, {@link
 * #withAttribute(String, Object)}) if you would rather not rely on positional arguments.
 */
public final class ExecutionContext {

    private final MessageId                   messageId;
    private final TraceId                     traceId;
    private final CorrelationId               correlationId;
    private final CausationId                 causationId;
    private final @Nullable TenantId          tenant;
    private final @Nullable SecurityPrincipal principal;
    private final @Nullable Instant           deadline;
    private final CancellationToken           cancellation;
    private final Map<String, Object>         attributes;

    /**
     * Public canonical constructor — validates every argument and defensively copies the
     * attributes map. Adapter modules, integration tests, and any external code that
     * hand-constructs an {@link ExecutionContext} go through this constructor.
     *
     * @throws NullPointerException if any non-nullable argument is {@code null}
     */
    public ExecutionContext(
            MessageId messageId,
            TraceId traceId,
            CorrelationId correlationId,
            CausationId causationId,
            @Nullable TenantId tenant,
            @Nullable SecurityPrincipal principal,
            @Nullable Instant deadline,
            CancellationToken cancellation,
            Map<String, Object> attributes) {
        this.messageId     = Objects.requireNonNull(messageId, "messageId");
        this.traceId       = Objects.requireNonNull(traceId, "traceId");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.causationId   = Objects.requireNonNull(causationId, "causationId");
        this.tenant        = tenant;
        this.principal     = principal;
        this.deadline      = deadline;
        this.cancellation  = Objects.requireNonNull(cancellation, "cancellation");
        // Defensive immutable copy — Map.copyOf is identity-on-immutable so the common path
        // (Map.of() from root() and Map.of(k,v) from withAttribute() fast-path) is
        // zero-allocation.
        this.attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    /**
     * Private skeleton constructor — assigns fields without any validation or defensive copy.
     * Used by {@link #unchecked} to skip the 6× {@code requireNonNull} + {@code Map.copyOf}
     * cost. Callers MUST guarantee every argument is non-null (except the nullable ones) and
     * the attributes map is already immutable.
     */
    private ExecutionContext(
            MessageId messageId,
            TraceId traceId,
            CorrelationId correlationId,
            CausationId causationId,
            @Nullable TenantId tenant,
            @Nullable SecurityPrincipal principal,
            @Nullable Instant deadline,
            CancellationToken cancellation,
            Map<String, Object> attributes,
            @SuppressWarnings("unused") boolean uncheckedMarker) {
        this.messageId     = messageId;
        this.traceId       = traceId;
        this.correlationId = correlationId;
        this.causationId   = causationId;
        this.tenant        = tenant;
        this.principal     = principal;
        this.deadline      = deadline;
        this.cancellation  = cancellation;
        this.attributes    = attributes;
    }

    /**
     * Package-private fast-path factory — bypasses every {@code Objects.requireNonNull} and
     * the {@link Map#copyOf} defensive wrap. ONLY callable from inside {@code core/runtime},
     * and ONLY safe when every argument is either (a) a constant the framework owns
     * ({@link Map#of()}, {@link CausationId#ROOT}), or (b) sourced from another already-
     * validated {@link ExecutionContext}'s accessor. JMH validates the unchecked path drops
     * ~3.5 ns per allocation vs the validated constructor.
     */
    static ExecutionContext unchecked(
            MessageId messageId,
            TraceId traceId,
            CorrelationId correlationId,
            CausationId causationId,
            @Nullable TenantId tenant,
            @Nullable SecurityPrincipal principal,
            @Nullable Instant deadline,
            CancellationToken cancellation,
            Map<String, Object> attributes) {
        return new ExecutionContext(
                messageId, traceId, correlationId, causationId,
                tenant, principal, deadline, cancellation, attributes, true);
    }

    public MessageId messageId() {
        return messageId;
    }

    public TraceId traceId() {
        return traceId;
    }

    public CorrelationId correlationId() {
        return correlationId;
    }

    public CausationId causationId() {
        return causationId;
    }

    public @Nullable TenantId tenant() {
        return tenant;
    }

    public @Nullable SecurityPrincipal principal() {
        return principal;
    }

    public @Nullable Instant deadline() {
        return deadline;
    }

    public CancellationToken cancellation() {
        return cancellation;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    /** Build a fresh root context: new ids, no deadline, fresh cancellation token, empty attrs. */
    public static ExecutionContext root() {
        java.util.concurrent.ThreadLocalRandom tlr = java.util.concurrent.ThreadLocalRandom.current();
        return unchecked(
                         new MessageId(net.nexus_flow.core.runtime.ids.FastUuid.v4(tlr)),
                         new TraceId(net.nexus_flow.core.runtime.ids.FastUuid.v4(tlr)),
                         new CorrelationId(net.nexus_flow.core.runtime.ids.FastUuid.v4(tlr)),
                         CausationId.ROOT,
                         null, null, null,
                         CancellationToken.create(),
                         Map.of());
    }

    /** Build a fresh root context with a deadline computed against the system UTC clock. */
    public static ExecutionContext rootWithTimeout(Duration timeout) {
        return rootWithTimeout(timeout, Clock.systemUTC());
    }

    /** Test-friendly factory accepting an explicit clock. */
    public static ExecutionContext rootWithTimeout(Duration timeout, Clock clock) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(clock, "clock");
        java.util.concurrent.ThreadLocalRandom tlr = java.util.concurrent.ThreadLocalRandom.current();
        return unchecked(
                         new MessageId(net.nexus_flow.core.runtime.ids.FastUuid.v4(tlr)),
                         new TraceId(net.nexus_flow.core.runtime.ids.FastUuid.v4(tlr)),
                         new CorrelationId(net.nexus_flow.core.runtime.ids.FastUuid.v4(tlr)),
                         CausationId.ROOT,
                         null, null,
                         clock.instant().plus(timeout),
                         CancellationToken.create(),
                         Map.of());
    }

    /** Derive a child context for a nested dispatch identified by {@code childMessageId}. */
    public ExecutionContext childContextFor(MessageId childMessageId) {
        Objects.requireNonNull(childMessageId, "childMessageId");
        return unchecked(
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

    /** Return a context identical to this one but with {@code key=value} added/replaced. */
    public ExecutionContext withAttribute(String key, Object value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Map<String, Object> next;
        int                 size = attributes.size();
        if (size == 0) {
            next = Map.of(key, value);
        } else if (size == 1 && !attributes.containsKey(key)) {
            Map.Entry<String, Object> only = attributes.entrySet().iterator().next();
            next = Map.of(only.getKey(), only.getValue(), key, value);
        } else {
            Map<String, Object> mut = HashMap.newHashMap(size + 1);
            mut.putAll(attributes);
            mut.put(key, value);
            next = Map.copyOf(mut);
        }
        return unchecked(
                         messageId, traceId, correlationId, causationId,
                         tenant, principal, deadline, cancellation, next);
    }

    /** Return a context identical to this one but with the supplied tenant. */
    public ExecutionContext withTenant(@Nullable TenantId tenant) {
        return unchecked(
                         messageId, traceId, correlationId, causationId,
                         tenant, principal, deadline, cancellation, attributes);
    }

    /** Return a context identical to this one but with the supplied principal. */
    public ExecutionContext withPrincipal(@Nullable SecurityPrincipal principal) {
        return unchecked(
                         messageId, traceId, correlationId, causationId,
                         tenant, principal, deadline, cancellation, attributes);
    }

    public boolean hasDeadline() {
        return deadline != null;
    }

    public boolean hasTenant() {
        return tenant != null;
    }

    public boolean hasPrincipal() {
        return principal != null;
    }

    /**
     * Throw {@link FlowCancellationException} if the cancellation token has been triggered, or
     * {@link FlowDeadlineExceededException} if the deadline has elapsed (per the system UTC
     * clock).
     */
    public void throwIfCancelledOrExpired() {
        throwIfCancelledOrExpired(Clock.systemUTC());
    }

    /** Test-friendly variant that checks cancellation and deadline against the supplied clock. */
    public void throwIfCancelledOrExpired(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        cancellation.throwIfCancellationRequested();
        if (deadline != null && !clock.instant().isBefore(deadline)) {
            throw new FlowDeadlineExceededException(deadline);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof ExecutionContext other))
            return false;
        return Objects.equals(messageId, other.messageId) && Objects.equals(traceId, other.traceId) && Objects.equals(correlationId,
                                                                                                                      other.correlationId) && Objects
                                                                                                                              .equals(causationId,
                                                                                                                                      other.causationId) && Objects
                                                                                                                                              .equals(tenant,
                                                                                                                                                      other.tenant) && Objects
                                                                                                                                                              .equals(principal,
                                                                                                                                                                      other.principal) && Objects
                                                                                                                                                                              .equals(deadline,
                                                                                                                                                                                      other.deadline) && Objects
                                                                                                                                                                                              .equals(cancellation,
                                                                                                                                                                                                      other.cancellation) && Objects
                                                                                                                                                                                                              .equals(attributes,
                                                                                                                                                                                                                      other.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                            messageId, traceId, correlationId, causationId,
                            tenant, principal, deadline, cancellation, attributes);
    }

    @Override
    public String toString() {
        return "ExecutionContext["
                + "messageId=" + messageId
                + ", traceId=" + traceId
                + ", correlationId=" + correlationId
                + ", causationId=" + causationId
                + ", tenant=" + tenant
                + ", principal=" + principal
                + ", deadline=" + deadline
                + ", cancellation=" + cancellation
                + ", attributes=" + attributes
                + "]";
    }
}
