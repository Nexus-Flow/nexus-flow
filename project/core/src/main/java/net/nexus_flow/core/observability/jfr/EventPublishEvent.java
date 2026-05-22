package net.nexus_flow.core.observability.jfr;

import jdk.jfr.*;
import org.jspecify.annotations.Nullable;

/**
 * JFR custom event emitted once per call to {@link
 * net.nexus_flow.core.cqrs.event.EventBus#dispatchResult( net.nexus_flow.core.ddd.DomainEvent,
 * net.nexus_flow.core.runtime.ExecutionContext, net.nexus_flow.core.runtime.ErrorPolicy)
 * EventBus.dispatchResult}.
 *
 * <p>Covers both the sequential listener delivery path and the opt-in parallel fan-out path. {@code
 * listenerCount} is the resolved {@link net.nexus_flow.core.runtime.registry.DispatchPlan#size()
 * DispatchPlan.size()} at dispatch time; {@code parallelFanOut} records whether the concurrent
 * execution path was selected for this particular dispatch.
 *
 * <p>String fields ({@code eventType}, {@code outcome}, {@code failureClass}) are populated only
 * after {@link jdk.jfr.Event#shouldCommit()} returns {@code true} to avoid unnecessary allocations
 * when no JFR recording is active.
 *
 * <p><strong>JFR annotations.</strong>
 *
 * <ul>
 * <li>{@code @Name("net.nexusflow.EventPublish")} — stable event name used in JFR configurations
 * and {@code Recording.enable(...)} calls. The {@code "net.nexusflow."} prefix avoids
 * collisions with JDK-internal events.
 * <li>{@code @Label("Event Publish")} — human-readable label shown in JDK Mission Control and
 * other JFR viewers.
 * <li>{@code @Category({"NexusFlow", "CQRS"})} — places the event under the {@code
 *       NexusFlow/CQRS} tree, matching the documented JFR category for all Nexus Flow core events.
 * <li>{@code @Description} — one-line summary displayed in JFR tooling.
 * <li>{@code @StackTrace(false)} — disables automatic stack-trace capture; the fan-out path is
 * too hot for per-dispatch stack captures. Enable selectively when diagnosing slow listeners.
 * </ul>
 *
 * <p><strong>Concurrency.</strong> JFR event instances are not thread-safe and must not be shared
 * across threads. Each dispatch site allocates its own instance; {@link jdk.jfr.Event#commit()}
 * writes lock-free into the JFR per-thread buffer. This design keeps the commit path entirely off
 * the garbage collection and scheduler hot paths.
 */
@Name("net.nexusflow.EventPublish")
@Label("Event Publish")
@Category({"NexusFlow", "CQRS"})
@Description("A domain event was published and fanned out through the per-runtime EventBus.")
@StackTrace(false)
public final class EventPublishEvent extends Event {

    /**
     * Fully-qualified type name of the domain event class that was published (e.g. {@code
     * com.example.OrderPlaced}). Populated only when {@link jdk.jfr.Event#shouldCommit()} returns
     * {@code true}.
     */
    @Label("Event Type")
    public @Nullable String eventType;

    /**
     * Number of listeners resolved in the {@link net.nexus_flow.core.runtime.registry.DispatchPlan
     * DispatchPlan} at dispatch time. Zero indicates no listeners were registered; the event would
     * have been silently discarded.
     */
    @Label("Listener Count")
    public int listenerCount;

    /**
     * {@code true} when the parallel fan-out path was engaged for this dispatch. Parallel fan-out
     * requires every listener registered for the event class to have opted in via {@code
     * parallelSafe()=true}. {@code false} on the default sequential path.
     */
    @Label("Parallel Fan-Out")
    public boolean parallelFanOut;

    /**
     * Outcome of the fan-out. One of:
     *
     * <ul>
     * <li>{@code "Success"} — all listeners executed without error.
     * <li>{@code "Failure"} — every listener raised an error (or the sole listener failed).
     * <li>{@code "PartialFailure"} — at least one listener succeeded and at least one failed under
     * {@code ErrorPolicy.collectFailures()}.
     * <li>{@code "Accepted"} — the event was handed off to the durable outbox.
     * </ul>
     */
    @Label("Outcome")
    @Description("One of: Success, Failure, PartialFailure, Accepted")
    public @Nullable String outcome;

    /**
     * Fully-qualified class name of the first failure encountered during fan-out (e.g. {@code
     * java.lang.IllegalStateException}), or {@code null} when {@link #outcome} is {@code "Success"}
     * or {@code "Accepted"}.
     */
    @Label("Failure Class")
    @Description("Fully-qualified class name of the surfaced failure, or null on success")
    public @Nullable String failureClass;
}
