package net.nexus_flow.core.cqrs.command;

import java.io.Serial;
import java.util.Objects;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.result.FlowError;

/**
 * back-pressure rejection surfaced when a handler's queue reaches {@link
 * HandlerBackpressureSettings#queueDepth()} and the configured {@link SaturationPolicy} cannot
 * accept the dispatch.
 *
 * <p>Extends {@link FlowError.Technical}: the cause is the rejection itself, and the {@link
 * ExecutionContext} active at the rejection site travels with the exception so that downstream
 * observers (e.g. the Outbox drain) can correlate the failure with the dispatch that triggered it.
 *
 * <p>Two instance accessors document the why: {@link #queueDepthAtRejection()} pins the queue depth
 * observed at the moment the policy fired, and {@link #policy()} echoes the policy itself so
 * consumers do not need to reach back into the handler settings.
 */
public final class SaturationRejectedException extends FlowError.Technical {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Class<?>         commandType;
    private final int              queueDepthAtRejection;
    private final SaturationPolicy policy;

    /**
     * Creates a saturation rejection carrying queue diagnostics.
     *
     * @param commandType           rejected command type, or {@code null} when unavailable
     * @param queueDepthAtRejection observed queue depth at rejection time
     * @param policy                saturation policy that rejected the dispatch
     * @param executionContext      execution context active at the rejection site
     */
    public SaturationRejectedException(
            Class<?> commandType,
            int queueDepthAtRejection,
            SaturationPolicy policy,
            ExecutionContext executionContext) {
        super(
              buildMessage(commandType, queueDepthAtRejection, policy),
              RejectionMarker.INSTANCE,
              executionContext);
        this.commandType           = commandType;
        this.queueDepthAtRejection = queueDepthAtRejection;
        this.policy                = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Returns the rejected command type.
     *
     * @return rejected command type, or {@code null} when unavailable
     */
    @SuppressWarnings(
        "java:S1452") // Type token; identifies the rejected command for observers without coupling to
    // its concrete type.
    public Class<?> commandType() {
        return commandType;
    }

    /**
     * Returns the queue depth observed when the rejection happened.
     *
     * @return queue depth at rejection time
     */
    public int queueDepthAtRejection() {
        return queueDepthAtRejection;
    }

    /**
     * Returns the policy that rejected the dispatch.
     *
     * @return saturation policy in effect at the rejection site
     */
    public SaturationPolicy policy() {
        return policy;
    }

    private static String buildMessage(Class<?> commandType, int depth, SaturationPolicy policy) {
        String name = commandType != null ? commandType.getSimpleName() : "<unknown>";
        return "Handler queue saturated for command "
                + name
                + " (queueDepth="
                + depth
                + ", policy="
                + policy
                + ")";
    }

    /**
     * Marker cause used to satisfy the {@link FlowError.Technical#Technical(String, Throwable,
     * ExecutionContext)} contract (cause must be non-null). The rejection itself is not a wrapping of
     * a deeper cause — it is the root failure — but the superclass insists on a non-null cause to
     * keep the general-purpose invariant for technical errors consistent.
     */
    public static final class RejectionMarker extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Singleton synthetic root cause for every {@link SaturationRejectedException}. The
         * marker carries no per-instance state; reusing one instance saves both the marker
         * allocation AND its stack-trace capture on every rejection. The marker's trace would
         * point to the {@code RejectionMarker} constructor, never the actual rejection site
         * (which is the {@link SaturationRejectedException}'s own trace), so the cached
         * marker loses no actionable debug information.
         */
        public static final RejectionMarker INSTANCE = new RejectionMarker();

        /** Creates the synthetic root cause used for rejection errors. */
        public RejectionMarker() {
            super("queue saturated", null, false, false);
        }
    }
}
