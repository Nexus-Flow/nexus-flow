package net.nexus_flow.core.scheduling;

import net.nexus_flow.core.runtime.CancellationToken;
import net.nexus_flow.core.runtime.ExecutionContext;

/**
 * Strategy that creates an {@link ExecutionContext} for each scheduled-command dispatch.
 *
 * <p>The factory is invoked once per dispatch attempt inside {@link ScheduledCommandWorker}. The
 * returned context is bound as the current {@link
 * net.nexus_flow.core.runtime.FlowScope#CURRENT_CONTEXT} for the duration of the dispatch so that
 * handlers, interceptors, and cross-cutting infrastructure (logging, tracing, cancellation) observe
 * a coherent context without an explicit parameter on the fire-and-forget {@link
 * net.nexus_flow.core.cqrs.command.CommandBus#dispatch(net.nexus_flow.core.cqrs.command.Command)
 * dispatch} path.
 *
 * <p><strong>Worker token:</strong> the factory receives the worker-lifetime {@link
 * CancellationToken} so it can embed it in the returned context. This ensures that {@link
 * ScheduledCommandWorker#shutdown()} cooperatively cancels any in-flight handler that polls {@link
 * ExecutionContext#throwIfCancelledOrExpired()}.
 *
 * <p><strong>Default factory:</strong> {@link ScheduledCommandConfig#DEFAULT_CONTEXT_FACTORY}
 * generates a fresh {@code MessageId}, {@code TraceId}, and {@code CorrelationId} for each record
 * so that every dispatch is independently traceable. Override via {@link
 * ScheduledCommandConfig.Builder#contextFactory(ScheduledDispatchContextFactory)} to inject
 * trace-propagated or deterministic IDs (e.g. derived from the record's surrogate key for
 * idempotent replay scenarios).
 */
@FunctionalInterface
public interface ScheduledDispatchContextFactory {

    /**
     * Create an {@link ExecutionContext} for dispatching {@code record}.
     *
     * @param record      the scheduled-command row about to be dispatched; never {@code null}
     * @param workerToken the worker-lifetime cancellation token; must be embedded in the returned
     *                    context so shutdown cancellation propagates to the handler; never {@code null}
     * @return the context to bind during dispatch; must not be {@code null}
     */
    ExecutionContext contextFor(ScheduledCommandRecord record, CancellationToken workerToken);
}
