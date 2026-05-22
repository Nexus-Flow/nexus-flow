package net.nexus_flow.core.cqrs.command;

import java.util.List;
import java.util.Optional;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.DomainEvent;
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
     * Drain {@code events} according to the runtime's outbox binding and kill-switch.
     *
     * @param events      events recorded by the handler, in aggregate-emission order; never {@code null};
     *                    empty ⇒ no-op.
     * @param runtime     owning runtime; its {@link FlowRuntime#outbox()} drives the routing.
     * @param eventBus    per-runtime event bus to inline-publish into when the kill-switch is OFF or no
     *                    outbox is bound.
     * @param sagaEnabled legacy saga flag threaded through to {@link EventBus#dispatch(DomainEvent,
     *                    boolean)} (semantics).
     */
    static void drain(
            List<DomainEvent> events, FlowRuntime runtime, EventBus eventBus, boolean sagaEnabled) {
        if (events == null || events.isEmpty()) {
            return;
        }

        Optional<OutboxConfig> outboxOpt = runtime.outbox();
        if (outboxOpt.isPresent()) {
            OutboxConfig cfg = outboxOpt.get();
            // Best-effort: the holder-bound ExecutionContext is not
            // exposed inside the executor; we rebuild a minimal one
            // (root) for the row metadata when no scope is bound.
            // will plumb the dispatch's ExecutionContext
            // through here so trace/correlation ids reach the row.
            ExecutionContext ctxForRow = FlowScope.current().orElseGet(ExecutionContext::root);
            OutboxAppender.appendDrainedEvents(
                                               events, ctxForRow, cfg.storage(), cfg.clock(), cfg.codec());
            if (cfg.useOutboxFanOut()) {
                // Kill-switch ON: OutboxWorker is the sole publisher.
                return;
            }
        }

        // Inline fan-out path: kill-switch OFF, or no outbox bound.
        for (DomainEvent event : events) {
            eventBus.dispatch(event, sagaEnabled);
        }
    }
}
