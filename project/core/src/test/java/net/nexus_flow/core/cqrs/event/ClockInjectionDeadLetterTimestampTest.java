package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.junit.jupiter.api.Test;

/**
 * verifies that the {@link FlowRuntime.Builder#clock(Clock) Clock SPI} is wired end-to-end into the
 * {@link DeadLetterEntry#occurredAt()} timestamp. This is the integration proof that an adapter
 * module (Spring / Quarkus / Micronaut) can inject the framework-supplied {@code Clock} bean and
 * get deterministic timestamps in dead-letter envelopes without monkey-patching {@code
 * Instant.now}.
 */
class ClockInjectionDeadLetterTimestampTest {

    private static final Instant FROZEN = Instant.parse("2030-01-15T10:00:00Z");

    public static final class BoomEvent extends AbstractDomainEvent {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        BoomEvent() {
            super("agg-1");
        }
    }

    public static final class BoomListener extends AbstractDomainEventListener<BoomEvent> {
        @Override
        public RetryPolicy retryPolicy() {
            return new RetryPolicy.FixedDelay(1, java.time.Duration.ZERO);
        }

        @Override
        public void handle(BoomEvent event) {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void deadLetterEntry_carriesInjectedClockTimestamp() {
        Clock                   frozen = Clock.fixed(FROZEN, ZoneOffset.UTC);
        InMemoryDeadLetterQueue dlq    = new InMemoryDeadLetterQueue(16);

        try (FlowRuntime rt = FlowRuntime.builder().clock(frozen).build()) {

            EventBus bus = rt.events();
            bus.deadLetterQueue(dlq);
            bus.register(new BoomListener());

            DispatchResult<Void> result =
                    bus.dispatchResult(new BoomEvent(), ExecutionContext.root(), ErrorPolicy.failFast());
            assertEquals(DispatchResult.Success.class, result.getClass());

            List<DeadLetterEntry> drained = dlq.drain();
            assertEquals(1, drained.size(), "exactly one dead-letter entry expected");
            assertEquals(
                         FROZEN,
                         drained.getFirst().occurredAt(),
                         "DeadLetterEntry.occurredAt must come from the injected Clock");
        }
    }
}
