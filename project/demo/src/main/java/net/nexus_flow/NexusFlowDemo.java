package net.nexus_flow;

import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.query.Query;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.interceptors.LoggingDispatchInterceptor;
import net.nexus_flow.core.runtime.result.DispatchResult;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Nexus Flow demo - Order Management use case.
 *
 * <p>Illustrates the canonical Nexus Flow programming model:
 *
 * <ol>
 *   <li>Bootstrap a {@link FlowRuntime} with an interceptor.</li>
 *   <li>Register command handlers, query handlers and event listeners.</li>
 *   <li>Dispatch a fire-and-forget command ({@code PlaceOrderCommand}).</li>
 *   <li>Dispatch a typed-result command with {@link DispatchResult} pattern matching.</li>
 *   <li>Issue a query and print the resulting read-model summary.</li>
 * </ol>
 *
 * <p>The runtime is owned inside a try-with-resources block; {@code close()} shuts down executors
 * gracefully and drains any pending outbox entries if an outbox is configured.
 */
public class NexusFlowDemo {

  private static final Logger LOG = System.getLogger(NexusFlowDemo.class.getName());

  /**
   * Entry point of the demo.
   *
   * <p>Bootstraps a minimal runtime, registers the sample handlers and listeners, dispatches a
   * place-order command, cancels the order through a typed-result command, and finally queries the
   * current order summary.
   *
   */
  static void main() {
    InMemoryOrderRepository orders = new InMemoryOrderRepository();

    try (FlowRuntime runtime = FlowRuntime.builder()
            .interceptor(new LoggingDispatchInterceptor())
            .handlers(new OrderAuditListener(),
                      new OrderCancellationAuditListener(),
                      new PlaceOrderCommandHandler(orders),
                      new CancelOrderCommandHandler(orders),
                      new GetOrderQueryHandler(orders))
            .build()) {

      dispatchPlaceOrder(runtime);
      dispatchCancelOrder(runtime);
      queryOrderSummary(runtime);
    }
    // FlowRuntime.close() is called here - executors shut down cleanly.
  }

  private static void dispatchPlaceOrder(FlowRuntime runtime) {
    // Fire-and-forget command. The handler creates an OrderAggregate, which records an
    // OrderPlacedEvent. The runtime drains that event after the handler returns and fans it out to
    // OrderAuditListener.
    Command<PlaceOrderCommand> placeOrder =
        Command.<PlaceOrderCommand>builder()
            .body(new PlaceOrderCommand("order-001", "prod-42", 3))
            .priority(10)
            .build();

    runtime.commands().dispatch(placeOrder);
  }

  private static void dispatchCancelOrder(FlowRuntime runtime) {
    // Typed-result command. dispatchAndReturnResult routes through the interceptor chain and
    // returns a sealed DispatchResult. Pattern matching keeps the success and failure paths explicit.
    Command<CancelOrderCommand> cancelCommand =
        Command.<CancelOrderCommand>builder()
            .body(new CancelOrderCommand("order-001", "Customer request"))
            .build();

    DispatchResult<?> result =
        runtime.commands()
            .dispatchAndReturnResult(cancelCommand, ExecutionContext.root(), ErrorPolicy.failFast());

    switch (result) {
      case DispatchResult.Success<?> success ->
          LOG.log(Level.INFO, "Cancel succeeded: {0}", success.value());
      case DispatchResult.Failure<?> failure ->
          LOG.log(Level.ERROR, "Cancel failed: {0}", failure.cause().getMessage());
      case DispatchResult.PartialFailure<?> partialFailure ->
          LOG.log(
              Level.WARNING,
              "Cancel partially failed: {0} error(s)",
              partialFailure.failures().size());
      case DispatchResult.Accepted<?> accepted ->
          LOG.log(Level.INFO, "Cancel accepted for durable processing: {0}", accepted.messageId());
    }
  }

  private static void queryOrderSummary(FlowRuntime runtime) {
    Query<GetOrderQuery> query =
        Query.<GetOrderQuery>builder().body(new GetOrderQuery("order-001")).build();

    String summary = runtime.queries().ask(query);
    LOG.log(Level.INFO, "Order summary: {0}", summary);
  }
}
