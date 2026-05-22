package net.nexus_flow.core.ring.dispatch;

import java.util.Objects;
import net.nexus_flow.core.ring.registry.HandlerRole;
import net.nexus_flow.core.ring.transport.RingTransportPrincipal;
import org.jspecify.annotations.Nullable;

/**
 * SPI consulted by {@link RingFrameRouter} before invoking
 * {@link LocalDispatchHandler#dispatch} on an inbound COMMAND_REQ / QUERY_REQ frame.
 *
 * <h2>Why this exists</h2>
 *
 * mTLS authenticates the transport. It does NOT authorize the operation. The original
 * design accepted any cross-pod dispatch from any peer that completed mTLS — a compromised
 * pod could issue arbitrary commands or query any tenant. The {@code DispatchAuthorizer}
 * closes that gap: every cross-pod dispatch must pass through an explicit allow / deny
 * decision keyed on the authenticated TLS principal, the requested role, the type, and the
 * tenant scope.
 *
 * <h2>Default implementations</h2>
 *
 * <ul>
 * <li>{@link #ALLOW_ALL} — admits every request. ONLY safe in single-tenant labs where the
 * ring is closed and every peer is fully trusted.
 * <li>{@link #DENY_ALL} — refuses every request. Useful for tests pinning the deny path.
 * <li>Production adapters typically implement this against a tenant-membership store, an
 * OPA / Open Policy Agent decision endpoint, or an LDAP-backed role lookup.
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * {@link #authorize} is invoked on the connection's reader virtual thread for every inbound
 * dispatch. Implementations MUST be thread-safe and SHOULD be cheap (microseconds, not
 * milliseconds) — slow authorization decisions block the reader VT for the offending peer.
 * Network-bound decisions (LDAP, OPA-over-HTTP) should be cached aggressively with a short
 * TTL.
 *
 * <h2>Decision shape</h2>
 *
 * Authorisation returns a sealed sum type so the call site can pattern-match without
 * boolean-and-string conventions:
 *
 * <pre>{@code
 * switch (auth.authorize(principal, role, tenant, type)) {
 *     case AuthorizationDecision.Allowed _ -> dispatch();
 *     case AuthorizationDecision.Denied d  -> reply(FORBIDDEN, d.reason());
 * }
 * }</pre>
 */
@FunctionalInterface
public interface DispatchAuthorizer {

    /**
     * Decide whether {@code principal} may dispatch the given role/type/tenant combination
     * against the LOCAL runtime via the ring.
     *
     * @param principal   the authenticated TLS principal; for plain-TCP connections this is the
     *                    {@link RingTransportPrincipal#anonymous(net.nexus_flow.core.ring.transport.PeerAddress)} value
     * @param role        COMMAND or QUERY
     * @param tenantId    the tenant scope from the envelope, or {@code null} for non-multi-tenant
     *                    deployments
     * @param payloadType the FQN of the command / query type
     * @return the structured decision; never {@code null}
     */
    AuthorizationDecision authorize(
            RingTransportPrincipal principal,
            HandlerRole role,
            @Nullable String tenantId,
            String payloadType);

    /** Outcomes of an {@link #authorize} call. Sealed for exhaustive pattern matching. */
    sealed interface AuthorizationDecision permits AuthorizationDecision.Allowed, AuthorizationDecision.Denied {

        /** Permits the dispatch. Singleton instance: {@link #INSTANCE}. */
        record Allowed() implements AuthorizationDecision {
            /** Singleton — no per-decision allocation. */
            public static final Allowed INSTANCE = new Allowed();
        }

        /**
         * Refuses the dispatch with a human-readable {@code reason} for the local logs. The
         * reason is NEVER echoed to the remote peer — the wire response is the sanitised
         * {@link net.nexus_flow.core.ring.wire.ProtocolErrorCode#FORBIDDEN} code.
         *
         * @param reason short diagnostic for operator triage; never {@code null}
         */
        record Denied(String reason) implements AuthorizationDecision {
            public Denied {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /** Admits every dispatch. ONLY safe for closed single-tenant labs. */
    DispatchAuthorizer ALLOW_ALL = (p, r, t, type) -> AuthorizationDecision.Allowed.INSTANCE;

    /** Refuses every dispatch. Useful for testing the deny path. */
    DispatchAuthorizer DENY_ALL = (p, r, t, type) -> new AuthorizationDecision.Denied("deny-all policy");
}
