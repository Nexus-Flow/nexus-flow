package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.CancellationToken;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;
import net.nexus_flow.core.runtime.result.DispatchResult;
import net.nexus_flow.core.runtime.result.FlowCancellationException;
import net.nexus_flow.core.runtime.result.FlowDeadlineExceededException;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link DurableDispatch} must not append to the outbox when the {@link
 * ExecutionContext} is already cancelled or past its deadline. Instead it returns a {@link
 * DispatchResult.Failure} carrying the matching runtime exception.
 */
class DurableDispatchIsCancellationAndDeadlineSafeTest {

    static final class Marker extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Marker(String aggId) {
            super(aggId);
        }
    }

    static final class Sender extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;
        private final UUID        id;

        Sender(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getAggregateId() {
            return id;
        }

        DomainEvent emit() {
            Marker m = new Marker(id.toString());
            recordEvent(m);
            return m;
        }
    }

    @Test
    void cancelledContext_returnsFailure_andDoesNotAppend() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        CancellationToken     token   = CancellationToken.create();
        token.cancel();
        ExecutionContext ctx =
                new ExecutionContext(
                        MessageId.random(),
                        TraceId.random(),
                        CorrelationId.random(),
                        CausationId.ROOT,
                        null,
                        null,
                        null,
                        token,
                        Map.of());

        DomainEvent event = new Sender(UUID.randomUUID()).emit();

        DispatchResult<Void> result =
                DurableDispatch.acceptAndAppend(event, ctx, storage, Clock.systemUTC());

        @SuppressWarnings("unchecked") DispatchResult.Failure<Void> failure = assertInstanceOf(DispatchResult.Failure.class, result);
        assertInstanceOf(
                         FlowCancellationException.class,
                         failure.cause(),
                         "cancellation must surface verbatim; got " + failure.cause());
        assertEquals(0, storage.snapshot().size(), "storage must be untouched when ctx is cancelled");
    }

    @Test
    void expiredDeadline_returnsFailure_andDoesNotAppend() {
        InMemoryOutboxStorage storage = new InMemoryOutboxStorage();
        // Deadline in the past — already expired.
        Instant          pastDeadline = Instant.now().minusSeconds(60);
        ExecutionContext ctx          =
                new ExecutionContext(
                        MessageId.random(),
                        TraceId.random(),
                        CorrelationId.random(),
                        CausationId.ROOT,
                        null,
                        null,
                        pastDeadline,
                        CancellationToken.create(),
                        Map.of());

        DomainEvent event = new Sender(UUID.randomUUID()).emit();

        DispatchResult<Void> result =
                DurableDispatch.acceptAndAppend(event, ctx, storage, Clock.systemUTC());

        @SuppressWarnings("unchecked") DispatchResult.Failure<Void> failure = assertInstanceOf(DispatchResult.Failure.class, result);
        assertInstanceOf(
                         FlowDeadlineExceededException.class,
                         failure.cause(),
                         "deadline expiry must surface verbatim; got " + failure.cause());
        assertEquals(0, storage.snapshot().size(), "storage must be untouched when deadline is past");
    }
}
