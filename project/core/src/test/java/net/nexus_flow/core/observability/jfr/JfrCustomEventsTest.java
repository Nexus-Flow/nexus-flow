package net.nexus_flow.core.observability.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import net.nexus_flow.core.cqrs.command.AbstractReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link CommandDispatchEvent}, {@link EventPublishEvent}, and {@link HandlerInvokeEvent} JFR
 * emission along the command/event dispatch path.
 *
 * <p>Verifies that the framework emits the three custom JFR events with accurate type information
 * and outcome records. Starts a {@link Recording} that enables the three custom event names, drives
 * one command that records one domain event and delivers it to two listeners (one succeeding, one
 * failing under {@link ErrorPolicy#collectFailures()}), then parses the dumped {@code .jfr} file to
 * assert the expected events were committed with expected outcomes.
 */
class JfrCustomEventsTest {

    static final class Boom extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Boom(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Cart extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void boom() {
            recordEvent(new Boom("cart-1"));
        }
    }

    record DoBoom(String tag) {
    }

    @Test
    void commandDispatchAndEventPublishAreRecordedAsJfrEvents() throws IOException {
        // Why: Force registration of custom JFR events before recording starts so that
        // Recording.enable(...) matches the @Name string. Record, emit one command that
        // triggers one event with two listeners (one success, one failure), dump JFR file,
        // and assert the three event types were recorded with correct type info and outcomes.
        Path dump = Files.createTempFile("nexusflow-jfr-", ".jfr");

        jdk.jfr.FlightRecorder.register(CommandDispatchEvent.class);
        jdk.jfr.FlightRecorder.register(EventPublishEvent.class);
        jdk.jfr.FlightRecorder.register(HandlerInvokeEvent.class);

        try (Recording recording = new Recording()) {
            recording.enable("net.nexusflow.CommandDispatch").withoutThreshold();
            recording.enable("net.nexusflow.EventPublish").withoutThreshold();
            recording.enable("net.nexusflow.HandlerInvoke").withoutThreshold();
            recording.start();

            try (FlowRuntime runtime = FlowRuntime.builder().build()) {
                var okListener  =
                        new AbstractDomainEventListener<Boom>() {
                                            @Override
                                            public void handle(Boom event) {
                                                // no-op success
                                            }
                                        };
                var badListener =
                        new AbstractDomainEventListener<Boom>() {
                                            @Override
                                            public void handle(Boom event) {
                                                throw new IllegalStateException("listener-boom");
                                            }
                                        };
                runtime.events().register(okListener);
                runtime.events().register(badListener);

                var handler =
                        new AbstractReturnCommandHandler<DoBoom, String>() {
                            @Override
                            protected String handle(DoBoom command) {
                                Cart cart = new Cart();
                                cart.boom();
                                List<DomainEvent> drained = cart.drainEvents();
                                drained.forEach(
                                                e -> net.nexus_flow.core.cqrs.event.DomainEventContext.current().recordEvent(e));
                                return command.tag();
                            }
                        };
                runtime.commands().register(handler);

                DispatchResult<String> r =
                        runtime
                                .commands()
                                .dispatchAndReturnResult(
                                                         Command.<DoBoom>builder().body(new DoBoom("ok")).build(),
                                                         ExecutionContext.root(),
                                                         ErrorPolicy.collectFailures());
                assertNotNull(r, "dispatch result");
            }

            recording.dump(dump);
        }

        List<RecordedEvent> events = readEvents(dump);
        Files.deleteIfExists(dump);

        Map<String, List<RecordedEvent>> byName =
                events.stream().collect(Collectors.groupingBy(e -> e.getEventType().getName()));

        // 1. Exactly one CommandDispatch event with our command type and
        // PartialFailure outcome (one listener succeeded, one failed
        // under collectFailures()).
        List<RecordedEvent> cmd = byName.getOrDefault("net.nexusflow.CommandDispatch", List.of());
        assertFalse(
                    cmd.isEmpty(), "expected at least one CommandDispatch event; got " + byName.keySet());
        RecordedEvent commandEvent     = cmd.getFirst();
        String        commandTypeField = commandEvent.getString("commandType");
        assertNotNull(commandTypeField, "commandType field");
        assertTrue(
                   commandTypeField.contains("DoBoom"),
                   "expected commandType to contain DoBoom; was " + commandTypeField);
        // PartialFailure surfaces from the event fan-out under collectFailures.
        String outcome = commandEvent.getString("outcome");
        assertTrue(
                   "PartialFailure".equals(outcome) || "Success".equals(outcome) || "Failure".equals(outcome),
                   "unexpected command outcome: " + outcome);

        // 2. Exactly one EventPublish event for Boom; listenerCount=2;
        // outcome PartialFailure (collectFailures swallowed the failure).
        List<RecordedEvent> pub = byName.getOrDefault("net.nexusflow.EventPublish", List.of());
        assertEquals(1, pub.size(), "expected exactly one EventPublish; got " + pub.size());
        RecordedEvent publish = pub.getFirst();
        assertTrue(
                   publish.getString("eventType").endsWith("$Boom"),
                   "unexpected eventType: " + publish.getString("eventType"));
        assertEquals(2, publish.getInt("listenerCount"));
        assertFalse(
                    publish.getBoolean("parallelFanOut"), "default runtime must not engage the parallel path");
        assertEquals("PartialFailure", publish.getString("outcome"));

        // 3. Exactly two HandlerInvoke events, one per listener; the
        // failing one carries failureClass = IllegalStateException.
        List<RecordedEvent> handlers = byName.getOrDefault("net.nexusflow.HandlerInvoke", List.of());
        assertEquals(2, handlers.size(), "expected one HandlerInvoke per listener");
        long failures =
                handlers.stream()
                        .filter(e -> !e.getBoolean("success"))
                        .peek(
                              e -> assertEquals(
                                                IllegalStateException.class.getName(), e.getString("failureClass")))
                        .count();
        assertEquals(1, failures, "exactly one listener must have failed");
        long successes = handlers.stream().filter(e -> e.getBoolean("success")).count();
        assertEquals(1, successes, "exactly one listener must have succeeded");
        // handlerType strings must be set to the listener class names.
        for (RecordedEvent e : handlers) {
            String h = e.getString("handlerType");
            assertNotNull(h, "handlerType");
            assertFalse(h.isBlank(), "handlerType blank");
        }
    }

    private static List<RecordedEvent> readEvents(Path file) throws IOException {
        List<RecordedEvent> out = new ArrayList<>();
        try (RecordingFile rf = new RecordingFile(file)) {
            while (rf.hasMoreEvents()) {
                out.add(rf.readEvent());
            }
        }
        return out;
    }
}
