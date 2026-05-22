package net.nexus_flow.core.observability.jfr;

import jdk.jfr.*;
import org.jspecify.annotations.Nullable;

/**
 * JFR custom event emitted once per individual listener invocation inside an event dispatch (both
 * the sequential and the parallel fan-out path).
 *
 * <p>{@code targetType} is the fully-qualified name of the concrete domain event class being
 * delivered; {@code handlerType} is the fully-qualified name of the listener class resolved via its
 * {@code MethodHandle} invoker. Together they allow flight recordings to pinpoint which listener
 * was slow or failing without needing a profiler attached.
 *
 * <p>{@code success} is {@code false} whenever the listener invocation threw, regardless of how the
 * surrounding {@link net.nexus_flow.core.runtime.ErrorPolicy ErrorPolicy} folds the failure (e.g.,
 * {@code collectFailures} swallows it at the dispatch level but this event still records the
 * per-listener outcome).
 *
 * <p>String fields are populated only after {@link jdk.jfr.Event#shouldCommit()} returns {@code
 * true} to avoid class-name string allocations when no JFR recording is active.
 *
 * <p><strong>JFR annotations.</strong>
 *
 * <ul>
 * <li>{@code @Name("net.nexusflow.HandlerInvoke")} — stable event name used in JFR configurations
 * and {@code Recording.enable(...)} calls. The {@code "net.nexusflow."} prefix avoids
 * collisions with JDK-internal events.
 * <li>{@code @Label("Handler Invoke")} — human-readable label shown in JDK Mission Control and
 * other JFR viewers.
 * <li>{@code @Category({"NexusFlow", "CQRS"})} — places the event under the {@code
 *       NexusFlow/CQRS} tree, matching the documented JFR category for all Nexus Flow core events.
 * <li>{@code @Description} — one-line summary displayed in JFR tooling.
 * <li>{@code @StackTrace(false)} — disables automatic stack-trace capture. Per-listener
 * invocations are frequent; capturing stack traces would add unacceptable overhead on the hot
 * fan-out path. Enable selectively in JFR configurations when call-site attribution is
 * needed.
 * </ul>
 *
 * <p><strong>Concurrency.</strong> JFR event instances are not thread-safe and must not be shared
 * across threads. Each listener invocation allocates its own event instance; {@link
 * jdk.jfr.Event#commit()} writes lock-free into the JFR per-thread buffer, keeping this entirely
 * off the synchronization critical path even under parallel fan-out.
 */
@Name("net.nexusflow.HandlerInvoke")
@Label("Handler Invoke")
@Category({"NexusFlow", "CQRS"})
@Description("A single listener invocation inside an event dispatch.")
@StackTrace(false)
public final class HandlerInvokeEvent extends Event {

    /**
     * Fully-qualified class name of the concrete domain event being delivered to the listener (e.g.
     * {@code com.example.OrderPlaced}). Set to the runtime class of the event, not a declared
     * supertype. Populated only when {@link jdk.jfr.Event#shouldCommit()} returns {@code true}.
     */
    @Label("Target Type")
    @Description("The concrete event class being delivered")
    public @Nullable String targetType;

    /**
     * Fully-qualified class name of the listener implementation that was invoked (e.g. {@code
     * com.example.OrderPlacedListener}). Resolved from the {@code MethodHandle}-backed {@link
     * net.nexus_flow.core.runtime.registry.HandlerInvoker HandlerInvoker} so it reflects the actual
     * concrete listener, not the abstract base class or the bus. Populated only when {@link
     * jdk.jfr.Event#shouldCommit()} returns {@code true}.
     */
    @Label("Handler Type")
    @Description("The listener class invoked for this delivery")
    public @Nullable String handlerType;

    /**
     * {@code true} if the listener completed without throwing; {@code false} if the invocation threw
     * any {@link Throwable}. This field records the per-listener outcome independently of how the
     * surrounding {@link net.nexus_flow.core.runtime.ErrorPolicy ErrorPolicy} handles the failure.
     */
    @Label("Success")
    public boolean success;

    /**
     * Fully-qualified class name of the exception thrown by the listener (e.g. {@code
     * java.lang.IllegalStateException}), or {@code null} when {@link #success} is {@code true}.
     */
    @Label("Failure Class")
    public @Nullable String failureClass;
}
