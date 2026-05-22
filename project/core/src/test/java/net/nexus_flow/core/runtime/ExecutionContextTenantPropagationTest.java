package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Map;
import net.nexus_flow.core.runtime.ids.*;
import org.junit.jupiter.api.Test;

/**
 * Pins the Phase B Change #3 contract: {@link ExecutionContext} carries optional {@link TenantId}
 * and {@link SecurityPrincipal} fields that propagate through every {@link
 * ExecutionContext#childContextFor(MessageId) child derivation}.
 *
 * <p>Without this propagation the framework cannot deliver on its multi-tenant promise (per-tenant
 * metrics tags, per-tenant rate limiting, audit logs scoped to the originating principal): the
 * tenant set on a top-level dispatch would silently degrade to {@code null} on every nested
 * dispatch, breaking the per-tenant observability story for hyperscale deployments.
 *
 * <p>Contract verified here:
 *
 * <ul>
 * <li>{@link ExecutionContext#root()} and {@link ExecutionContext#rootWithTimeout(Duration)} both
 * return contexts with {@code tenant=null} and {@code principal=null}.
 * <li>{@link ExecutionContext#withTenant(TenantId)} / {@link
 * ExecutionContext#withPrincipal(SecurityPrincipal)} return a context with the field replaced
 * (copy-on-write; original is unchanged).
 * <li>Both fields propagate through {@link ExecutionContext#childContextFor(MessageId)}.
 * <li>{@code withTenant(null)} / {@code withPrincipal(null)} clear the field.
 * <li>{@link ExecutionContext#hasTenant()} / {@link ExecutionContext#hasPrincipal()} mirror the
 * null-ness of the fields.
 * <li>{@link TenantId} rejects blank/null values; {@link NamedPrincipal} rejects blank names.
 * <li>{@link AnonymousPrincipal#INSTANCE} is the singleton returned by {@link
 * SecurityPrincipal#anonymous()}.
 * </ul>
 */
class ExecutionContextTenantPropagationTest {

    @Test
    void root_hasNoTenantNorPrincipal() {
        ExecutionContext ctx = ExecutionContext.root();
        assertNull(ctx.tenant(), "root() must have no tenant by default");
        assertNull(ctx.principal(), "root() must have no principal by default");
        assertFalse(ctx.hasTenant());
        assertFalse(ctx.hasPrincipal());
    }

    @Test
    void rootWithTimeout_hasNoTenantNorPrincipal() {
        ExecutionContext ctx = ExecutionContext.rootWithTimeout(Duration.ofMinutes(1));
        assertNull(ctx.tenant());
        assertNull(ctx.principal());
    }

    @Test
    void withTenant_setsAndCopiesOnWrite() {
        ExecutionContext base   = ExecutionContext.root();
        TenantId         acme   = TenantId.of("acme");
        ExecutionContext scoped = base.withTenant(acme);

        assertSame(acme, scoped.tenant(), "withTenant must store the supplied instance verbatim");
        assertTrue(scoped.hasTenant());
        // Copy-on-write: original ctx must remain unchanged.
        assertNull(base.tenant(), "withTenant must NOT mutate the original context");
        // Identity preserved on unrelated fields.
        assertSame(base.messageId(), scoped.messageId());
        assertSame(base.traceId(), scoped.traceId());
        assertSame(base.correlationId(), scoped.correlationId());
    }

    @Test
    void withPrincipal_setsAndCopiesOnWrite() {
        ExecutionContext  base   = ExecutionContext.root();
        SecurityPrincipal alice  = SecurityPrincipal.named("alice");
        ExecutionContext  scoped = base.withPrincipal(alice);

        assertSame(alice, scoped.principal());
        assertTrue(scoped.hasPrincipal());
        assertNull(base.principal());
    }

    @Test
    void childContextFor_propagatesTenantAndPrincipal() {
        TenantId          acme   = TenantId.of("acme");
        SecurityPrincipal alice  = SecurityPrincipal.named("alice", Map.of("role", "admin"));
        ExecutionContext  parent = ExecutionContext.root().withTenant(acme).withPrincipal(alice);

        MessageId        childMessageId = MessageId.random();
        ExecutionContext child          = parent.childContextFor(childMessageId);

        assertSame(
                   acme,
                   child.tenant(),
                   "child must inherit the parent's tenant — required for per-tenant metrics on nested "
                           + "dispatches");
        assertSame(
                   alice,
                   child.principal(),
                   "child must inherit the parent's principal — required for audit-trail continuity");
        // Standard child invariants still hold.
        assertSame(childMessageId, child.messageId());
        assertEquals(parent.messageId().asCausation(), child.causationId());
    }

    @Test
    void withTenant_null_clearsTenant() {
        ExecutionContext scoped  = ExecutionContext.root().withTenant(TenantId.of("acme"));
        ExecutionContext cleared = scoped.withTenant(null);
        assertNull(cleared.tenant());
        assertFalse(cleared.hasTenant());
        // Original scoped context unchanged.
        assertNotNull(scoped.tenant());
    }

    @Test
    void withPrincipal_null_clearsPrincipal() {
        ExecutionContext scoped  = ExecutionContext.root().withPrincipal(SecurityPrincipal.named("a"));
        ExecutionContext cleared = scoped.withPrincipal(null);
        assertNull(cleared.principal());
        assertFalse(cleared.hasPrincipal());
    }

    @Test
    void tenantId_rejectsNullAndBlank() {
        assertThrows(NullPointerException.class, () -> TenantId.of(null));
        assertThrows(IllegalArgumentException.class, () -> TenantId.of(""));
        assertThrows(IllegalArgumentException.class, () -> TenantId.of("   "));
    }

    @Test
    void tenantId_isCaseSensitive_andValueEqual() {
        assertEquals(TenantId.of("acme"), TenantId.of("acme"));
        assertEquals(TenantId.of("acme").hashCode(), TenantId.of("acme").hashCode());
        // Different casing is NOT equal — hosts that need case-insensitive equality canonicalise first.
        assertNotEquals(TenantId.of("acme"), TenantId.of("Acme"));
    }

    @Test
    void anonymousPrincipal_isSingleton() {
        assertSame(SecurityPrincipal.anonymous(), SecurityPrincipal.anonymous());
        assertSame(AnonymousPrincipal.INSTANCE, SecurityPrincipal.anonymous());
        assertEquals("anonymous", SecurityPrincipal.anonymous().name());
        assertTrue(SecurityPrincipal.anonymous().attributes().isEmpty());
    }

    @Test
    void namedPrincipal_rejectsBlankName() {
        assertThrows(NullPointerException.class, () -> SecurityPrincipal.named(null));
        assertThrows(IllegalArgumentException.class, () -> SecurityPrincipal.named(""));
        assertThrows(IllegalArgumentException.class, () -> SecurityPrincipal.named("   "));
    }

    @Test
    void namedPrincipal_carriesAttributes() {
        Map<String, Object> claims = Map.of("scope", "read", "issuer", "auth-svc");
        SecurityPrincipal   p      = SecurityPrincipal.named("bob", claims);
        assertEquals("bob", p.name());
        assertEquals(claims, p.attributes());
    }

    @Test
    void canonicalConstructor_acceptsNullTenantAndPrincipal() {
        // Explicit null arguments for both fields must be legal — single-tenant / unauthenticated
        // dispatches are a first-class case, not an error.
        ExecutionContext ctx =
                new ExecutionContext(
                        MessageId.random(),
                        TraceId.random(),
                        CorrelationId.random(),
                        CausationId.ROOT,
                        null,
                        null,
                        null,
                        CancellationToken.create(),
                        Map.of());
        assertNull(ctx.tenant());
        assertNull(ctx.principal());
    }

    @Test
    void childContext_withClearedTenant_propagatesNull() {
        ExecutionContext withTenant      = ExecutionContext.root().withTenant(TenantId.of("acme"));
        ExecutionContext clearedAtParent = withTenant.withTenant(null);
        ExecutionContext child           = clearedAtParent.childContextFor(MessageId.random());
        assertNull(child.tenant(), "if parent cleared its tenant, child must inherit null");
    }
}
