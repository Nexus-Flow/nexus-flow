package net.nexus_flow.core.ring.dispatch;

import java.util.Objects;
import java.util.OptionalLong;
import net.nexus_flow.core.ring.registry.HandlerRole;
import net.nexus_flow.core.ring.transport.RingTransportPrincipal;

/**
 * Adapter SPI invoked by {@link RingFrameRouter} for every inbound cross-pod request.
 *
 * <h2>Implementation contract</h2>
 *
 * <ul>
 * <li>Synchronous: invoked on the connection's reader VT; blocking is allowed but
 * implementations MUST honor {@link LocalDispatchContext#localDeadlineNanos()} so a
 * hanging local handler does not pin the reader VT past the deadline.
 * <li>Total: always returns a non-null {@link DispatchResponseEnvelope}; never throws.
 * Internal failures map to {@link DispatchResponseEnvelope#failure} with a {@link
 * net.nexus_flow.core.ring.wire.ProtocolErrorCode}; missing handlers map to
 * {@link DispatchResponseEnvelope#notFound}.
 * <li>Correlation echo: the returned envelope MUST carry the same correlation id as the
 * request.
 * <li>Sanitisation: the returned envelope's {@code reason} string MUST NOT contain raw
 * exception messages, stack-trace fragments, or any other internal diagnostic. The
 * sender's local logs already hold the rich diagnostic indexed by correlation id.
 * </ul>
 */
@FunctionalInterface
public interface LocalDispatchHandler {

    /**
     * Dispatch one inbound request against the local runtime.
     *
     * @param ctx the dispatch context carrying the decoded request, role, principal, and
     *            local-clock deadline; never {@code null}
     * @return the response envelope ready to send on the wire
     */
    DispatchResponseEnvelope dispatch(LocalDispatchContext ctx);

    /**
     * Context handed to {@link #dispatch}. Bundles the decoded request, the authenticated
     * TLS principal, the role discriminator, and the local-clock deadline derived from the
     * sender's relative remaining-budget — letting the handler check expiry against its OWN
     * monotonic clock without worrying about cross-peer wall-clock skew.
     *
     * @param request            the decoded request envelope
     * @param role               COMMAND or QUERY (matches {@code request.role()})
     * @param principal          the authenticated TLS principal of the sender connection
     * @param localDeadlineNanos {@link System#nanoTime()}-relative deadline, or empty if the
     *                           sender specified no deadline
     */
    record LocalDispatchContext(
                                DispatchRequestEnvelope request,
                                HandlerRole role,
                                RingTransportPrincipal principal,
                                OptionalLong localDeadlineNanos) {

        public LocalDispatchContext {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(localDeadlineNanos, "localDeadlineNanos");
        }

        /** {@code true} when the local-clock deadline is in the past. */
        public boolean isDeadlineExpired() {
            return localDeadlineNanos.isPresent() && System.nanoTime() >= localDeadlineNanos.getAsLong();
        }
    }
}
