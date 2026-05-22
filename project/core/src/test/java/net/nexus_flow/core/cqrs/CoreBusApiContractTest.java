package net.nexus_flow.core.cqrs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.event.RetryPolicy;
import net.nexus_flow.core.cqrs.query.AbstractQueryHandler;
import net.nexus_flow.core.cqrs.query.Query;
import net.nexus_flow.core.cqrs.query.QueryBus;
import net.nexus_flow.core.cqrs.query.QuerySettings;
import net.nexus_flow.core.cqrs.query.exceptions.QueryHandlerExecutionError;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for core CQRS bus APIs. Verifies the behavior contracts of command, query, and
 * event buses, including deadline enforcement, header immutability, event listener retry and error
 * handling, and timeout behavior.
 */
class CoreBusApiContractTest {

    record Echo(String value) {
    }

    record SlowQuery(String value) {
    }

    static final class InstrumentedEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        private final int amount;

        InstrumentedEvent(String aggregateId, int amount) {
            super(aggregateId);
            this.amount = amount;
        }

        InstrumentedEvent(String aggregateId, int amount, Map<String, String> headers) {
            super(aggregateId, headers);
            this.amount = amount;
        }

        int amount() {
            return amount;
        }
    }

    @Test
    void commandBuilderPreservesDeadlineAndHeaders() {
        // Commands must preserve deadline and header metadata without external mutation.
        Instant             deadline      = Instant.parse("2030-01-01T00:00:00Z");
        Map<String, String> sourceHeaders = new LinkedHashMap<>();
        sourceHeaders.put("trace-id", "trace-1");

        Command<Echo> command =
                Command.<Echo>builder()
                        .body(new Echo("hello"))
                        .deadline(deadline)
                        .header("correlation-id", "corr-1")
                        .headers(sourceHeaders)
                        .build();

        sourceHeaders.put("trace-id", "mutated");

        assertEquals(deadline, command.getDeadline());
        assertEquals("corr-1", command.getHeaders().get("correlation-id"));
        assertEquals("trace-1", command.getHeaders().get("trace-id"));
        assertThrows(
                     UnsupportedOperationException.class,
                     () -> command.getHeaders().put("tenant-id", "tenant-1"));
    }

    @Test
    void domainEventExposesStableTypeAndImmutableHeaders() {
        // Domain events must expose event type and immutable header collections.
        Map<String, String> sourceHeaders = new LinkedHashMap<>();
        sourceHeaders.put("correlation-id", "corr-1");
        InstrumentedEvent event = new InstrumentedEvent("agg-1", 42, sourceHeaders);

        sourceHeaders.put("correlation-id", "mutated");

        assertEquals("InstrumentedEvent", event.eventType());
        assertEquals("corr-1", event.getHeaders().get("correlation-id"));
        assertThrows(
                     UnsupportedOperationException.class, () -> event.getHeaders().put("trace-id", "trace-1"));
    }

    @Test
    void queryBusWrapsTimeoutFromHandlerSettings() {
        // Query handlers with configured timeouts must throw QueryHandlerExecutionError on timeout.
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            QueryBus queryBus = runtime.queries();
            var      handler  =
                    new AbstractQueryHandler<SlowQuery, String>() {
                                          @Override
                                          public QuerySettings settings() {
                                              return QuerySettings.withTimeout(Duration.ofMillis(10));
                                          }

                                          @Override
                                          public String handle(SlowQuery query) {
                                              LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
                                              return "late";
                                          }
                                      };
            queryBus.register(handler);
            try {
                QueryHandlerExecutionError error =
                        assertThrows(
                                     QueryHandlerExecutionError.class,
                                     () -> queryBus.ask(Query.<SlowQuery>builder().body(new SlowQuery("x")).build()));

                assertInstanceOf(TimeoutException.class, error.getCause());
            } finally {
                queryBus.unregister(handler);
            }
        }
    }

    @Test
    void eventBusAppliesFilterRetryAndErrorHandlerContracts() {
        // Event listeners must apply filter, retry, and error handler contracts correctly.
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            AtomicInteger              attempts  = new AtomicInteger();
            AtomicReference<Throwable> swallowed = new AtomicReference<>();
            var                        listener  =
                    new AbstractDomainEventListener<InstrumentedEvent>() {
                                                             @Override
                                                             public boolean filter(InstrumentedEvent event) {
                                                                 return event.amount() >= 10;
                                                             }

                                                             @Override
                                                             public RetryPolicy retryPolicy() {
                                                                 return new RetryPolicy.FixedDelay(3, Duration.ZERO);
                                                             }

                                                             @Override
                                                             public net.nexus_flow.core.cqrs.event.EventListenerErrorHandler<InstrumentedEvent> errorHandler() {
                                                                 return (event, cause) -> swallowed.set(cause);
                                                             }

                                                             @Override
                                                             public void handle(InstrumentedEvent event) {
                                                                 attempts.incrementAndGet();
                                                                 throw new IllegalStateException("boom-" + event.amount());
                                                             }
                                                         };
            runtime.events().register(listener);
            try {
                runtime.events().dispatch(new InstrumentedEvent("agg-1", 5), true);
                runtime.events().dispatch(new InstrumentedEvent("agg-1", 50), true);

                assertEquals(3, attempts.get());
                assertEquals("boom-50", swallowed.get().getMessage());
            } finally {
                runtime.events().unregister(listener);
            }
        }
    }

    @Test
    void expiredCommandDeadlineFailsBeforeQueuedExecution() {
        // Commands with expired deadlines must fail before being queued for handler execution.
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            AtomicInteger handled = new AtomicInteger();
            var           handler =
                    new AbstractReturnCommandHandler<Echo, String>() {
                                              @Override
                                              public int getConcurrencyLevel() {
                                                  return 1;
                                              }

                                              @Override
                                              protected String handle(Echo command) {
                                                  handled.incrementAndGet();
                                                  return command.value();
                                              }
                                          };
            runtime.commands().register(handler);
            try {
                Command<Echo> command =
                        Command.<Echo>builder()
                                .body(new Echo("late"))
                                .deadline(Instant.parse("2000-01-01T00:00:00Z"))
                                .build();

                net.nexus_flow.core.cqrs.command.exceptions.CommandHandlerExecutionError error =
                        assertThrows(
                                     net.nexus_flow.core.cqrs.command.exceptions.CommandHandlerExecutionError.class,
                                     () -> runtime.commands().dispatchAndReturn(command));

                net.nexus_flow.core.runtime.result.FlowDeadlineExceededException deadlineExceeded =
                        assertInstanceOf(
                                         net.nexus_flow.core.runtime.result.FlowDeadlineExceededException.class,
                                         error.getCause());
                assertEquals(command.getDeadline(), deadlineExceeded.deadline());
                assertEquals(0, handled.get());
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }
}
