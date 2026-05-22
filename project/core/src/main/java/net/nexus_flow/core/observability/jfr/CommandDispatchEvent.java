package net.nexus_flow.core.observability.jfr;

import jdk.jfr.*;
import org.jspecify.annotations.Nullable;

/**
 * JFR custom event emitted once per call to {@link
 * net.nexus_flow.core.cqrs.command.CommandBus#dispatchAndReturnResult(
 * net.nexus_flow.core.cqrs.command.Command, net.nexus_flow.core.runtime.ExecutionContext,
 * net.nexus_flow.core.runtime.ErrorPolicy) CommandBus.dispatchAndReturnResult}.
 *
 * <p>This class is an intentionally thin {@link Event} subclass with no behavior: the
 * instrumentation site allocates the event, calls {@link Event#begin()}, performs the dispatch
 * work, and then — only if {@link Event#shouldCommit()} returns {@code true} — populates the string
 * fields and calls {@link Event#commit()}. This guard is important for performance: it avoids
 * class-name string allocations on every dispatch when no JFR recording is active.
 *
 * <p><strong>JFR annotations.</strong>
 *
 * <ul>
 * <li>{@code @Name("net.nexusflow.CommandDispatch")} — the stable event name used to enable or
 * filter this event in JFR configurations and {@code Recording.enable(...)} calls. The {@code
 *       "net.nexusflow."} prefix avoids collisions with JDK-internal events.
 * <li>{@code @Label("Command Dispatch")} — human-readable label shown in JDK Mission Control and
 * other JFR viewers.
 * <li>{@code @Category({"NexusFlow", "CQRS"})} — places the event under the {@code
 *       NexusFlow/CQRS} tree in JFR viewers, matching the documented JFR category for all Nexus
 * Flow core events.
 * <li>{@code @Description} — one-line summary displayed in JFR tooling.
 * <li>{@code @StackTrace(false)} — disables automatic stack-trace capture for this event. Command
 * dispatch is a hot path; capturing a stack trace on every call would add unacceptable
 * overhead. Enable selectively in JFR configurations when call-site attribution is needed for
 * diagnosis.
 * </ul>
 *
 * <p><strong>No external dependencies.</strong> {@code jdk.jfr} ships with the JDK; this event type
 * introduces no compile-time or runtime dependencies beyond the JDK module graph.
 *
 * <p><strong>Concurrency.</strong> JFR event instances are not thread-safe and must not be shared
 * across threads. Each dispatch site allocates its own instance; {@link Event#commit()} performs a
 * lock-free write into the per-thread JFR buffer. This design keeps the commit path entirely off
 * the garbage collection and scheduler hot paths.
 */
@Name("net.nexusflow.CommandDispatch")
@Label("Command Dispatch")
@Category({"NexusFlow", "CQRS"})
@Description("A command was dispatched through the per-runtime CommandBus.")
@StackTrace(false)
public final class CommandDispatchEvent extends Event {

    /**
     * Fully-qualified type name of the command body record (e.g. {@code
     * com.example.PlaceOrderCommand}). Populated only when {@link Event#shouldCommit()} returns
     * {@code true} to avoid string allocation on every dispatch when no JFR recording is active.
     */
    @Label("Command Type")
    public @Nullable String commandType;

    /**
     * Outcome of the dispatch. One of:
     *
     * <ul>
     * <li>{@code "Success"} — the handler executed without error.
     * <li>{@code "Failure"} — the handler or an interceptor raised an unrecoverable error.
     * <li>{@code "PartialFailure"} — the handler succeeded but one or more downstream event
     * listeners failed under {@code ErrorPolicy.collectFailures()}.
     * <li>{@code "Accepted"} — the command was handed off to the durable outbox.
     * <li>{@code "NotRegistered"} — no handler was registered for the command type.
     * </ul>
     */
    @Label("Outcome")
    @Description("One of: Success, Failure, PartialFailure, Accepted, NotRegistered")
    public @Nullable String outcome;

    /**
     * Fully-qualified class name of the exception that caused the failure (e.g. {@code
     * java.lang.IllegalStateException}), or {@code null} when {@link #outcome} is {@code "Success"}
     * or {@code "Accepted"}.
     */
    @Label("Failure Class")
    @Description("Fully-qualified class name of the surfaced failure, or null on success")
    public @Nullable String failureClass;
}
