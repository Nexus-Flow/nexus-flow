package net.nexus_flow.core.cqrs.command;

import java.util.List;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.outbox.OutboxAppendBackpressureSettings;
import net.nexus_flow.core.outbox.OutboxAppendRejectedException;
import net.nexus_flow.core.outbox.OutboxAppender;
import net.nexus_flow.core.outbox.OutboxConfig;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.FlowScope;

/**
 * Post-handler domain-event drain policy (closeout).
 *
 * <p>Centralizes the outbox-vs-inline routing decision that was previously duplicated across the
 * fire-and-forget and value-producing command executor paths. Both call sites delegate to {@link
 * #drain(List, FlowRuntime, EventBus, boolean)} so the routing matrix has exactly one source of
 * truth.
 *
 * <h2>Decision matrix</h2>
 *
 * <table>
 * <caption>routing matrix</caption>
 * <tr><th>{@link FlowRuntime#outbox()}</th>
 * <th>{@link OutboxConfig#useOutboxFanOut()}</th>
 * <th>Behaviour</th></tr>
 * <tr><td>empty</td><td>—</td>
 * <td>Inline fan-out only (default mode).</td></tr>
 * <tr><td>present</td><td>false</td>
 * <td>Append every event to the outbox <strong>and</strong>
 * fan out inline (staged-migration mode).</td></tr>
 * <tr><td>present</td><td>true</td>
 * <td>Append every event to the outbox; suppress the inline
 * fan-out — the {@code OutboxWorker} becomes the sole
 * publisher (kill-switch ON).</td></tr>
 * </table>
 *
 * <h2>Why per-runtime, not per-handler?</h2>
 *
 * The contract documented on {@link FlowRuntime#outbox()} pins the outbox routing as a
 * <em>runtime-level</em> property: once you bind an outbox, every command handler running on that
 * runtime drains through it. A per-handler opt-in is layered on top via {@link
 * net.nexus_flow.core.runtime.ExecutionMode#asynchronousDurable()}, which asserts at resolve time
 * (see {@link net.nexus_flow.core.runtime.ExecutionStrategyResolver}) that the outbox is bound.
 * Handlers that do not declare durable mode still drain through the outbox when one is bound — this
 * is intentional and pinned by {@code SyncDispatcherSkipsFanOutWhenOutboxKillSwitchOnTest}.
 *
 * <h2>Lifecycle invariant</h2>
 *
 * The {@code DomainEventContext} from which {@code events} was drained MUST be cleared by the
 * caller after this method returns — both code paths in the two executors already clear the holder
 * unconditionally, which keeps the legacy {@code DefaultCommandBus.dispatchAndReturnResultBody}
 * Step 2 (re-drain from the holder) from observing the same events twice.
 */
final class HandlerEventDrain {

    private HandlerEventDrain() {
        // utility — no instances
    }

    /**
     * Drain {@code events} with PRE-RESOLVED config — the executor cached
     * {@code outboxConfig} and {@code useOutboxFanOut} once at construction so this hot path
     * does NOT pay for {@link FlowRuntime#outbox()} (Optional allocation) +
     * {@link OutboxConfig#useOutboxFanOut()} (instanceof check) on every call. At 1 M
     * command/sec this saves the per-drain Optional allocation cost.
     *
     * @param events          events to drain in aggregate-emission order; never {@code null};
     *                        empty is a no-op
     * @param outboxConfig    the runtime's outbox config, resolved ONCE at executor
     *                        construction; {@code null} when no outbox is bound (inline-only
     *                        shape)
     * @param useOutboxFanOut pre-resolved kill-switch flag; ignored when {@code outboxConfig}
     *                        is {@code null}
     * @param eventBus        per-runtime event bus to inline-publish into when the
     *                        kill-switch is OFF or no outbox is bound
     * @param sagaEnabled     legacy saga flag threaded through to {@link
     *                        EventBus#dispatch(DomainEvent, boolean)}
     */
    static void drain(
            List<DomainEvent> events,
            @org.jspecify.annotations.Nullable OutboxConfig outboxConfig,
            boolean useOutboxFanOut,
            EventBus eventBus,
            boolean sagaEnabled) {
        if (events == null || events.isEmpty()) {
            return;
        }

        if (outboxConfig != null) {
            BackpressureOutcome outcome = applyAppendBackpressure(outboxConfig);
            if (outcome == BackpressureOutcome.PROCEED) {
                ExecutionContext ctxForRow = FlowScope.current().orElseGet(ExecutionContext::root);
                OutboxAppender.appendDrainedEvents(
                                                   events, ctxForRow, outboxConfig.storage(), outboxConfig.clock(),
                                                   outboxConfig.codec());
            }
            if (useOutboxFanOut) {
                // Kill-switch ON: OutboxWorker is the sole publisher.
                return;
            }
        }

        // Inline fan-out path: kill-switch OFF, or no outbox bound.
        for (DomainEvent event : events) {
            eventBus.dispatch(event, sagaEnabled);
        }
    }

    /**
     * Back-compat overload that resolves {@code runtime.outbox()} and {@code useOutboxFanOut}
     * on each call. Used by callers that do not have access to the executor's cached fields
     * (e.g. {@code DefaultCommandBus.dispatchAndReturnResultBody} which lives outside the
     * executor's lifecycle). Prefer the pre-resolved overload above on hot paths.
     */
    static void drain(
            List<DomainEvent> events, FlowRuntime runtime, EventBus eventBus, boolean sagaEnabled) {
        OutboxConfig cfg = runtime.outbox().orElse(null);
        drain(events, cfg, cfg != null && cfg.useOutboxFanOut(), eventBus, sagaEnabled);
    }

    /** Outcome the drain loop uses to branch after the backpressure check. */
    private enum BackpressureOutcome {
        /** Append the batch normally. */
        PROCEED,
        /** Silently drop the durable append; inline fan-out may still run. */
        DROP
    }

    /**
     * Consult the outbox config's {@link OutboxAppendBackpressureSettings} against the
     * storage's {@link net.nexus_flow.core.outbox.OutboxStorage#pendingCount()}. Throws
     * {@link OutboxAppendRejectedException} when the policy is REJECT and the threshold is
     * crossed.
     */
    private static BackpressureOutcome applyAppendBackpressure(OutboxConfig cfg) {
        OutboxAppendBackpressureSettings bp = cfg.appendBackpressure();
        if (bp.policy() == OutboxAppendBackpressureSettings.Policy.UNLIMITED) {
            return BackpressureOutcome.PROCEED;
        }
        long pending = cfg.storage().pendingCount();
        if (pending < 0L) {
            // Backend doesn't expose a pending count; we have no signal to act on.
            return BackpressureOutcome.PROCEED;
        }
        if (pending < bp.maxPendingRows()) {
            return BackpressureOutcome.PROCEED;
        }
        return switch (bp.policy()) {
            case REJECT    -> throw new OutboxAppendRejectedException(pending, bp.maxPendingRows());
            case DROP      -> BackpressureOutcome.DROP;
            case UNLIMITED -> BackpressureOutcome.PROCEED; // unreachable, kept for exhaustiveness
        };
    }
}
