package net.nexus_flow.core.runtime.dispatch;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.nexus_flow.core.cqrs.command.Command;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.interceptors.LoggingDispatchInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * a dispatch issued <em>after</em> {@link FlowRuntime#close()} must short-circuit with {@link
 * IllegalStateException} <strong>before</strong> the {@link DispatchInterceptor} onion is
 * materialised.
 *
 * <p>The guarantee is observable via {@link LoggingDispatchInterceptor}, which emits a {@code
 * java.util.logging} record on every traversal. We pin the contract by attaching a recording {@link
 * Handler} to the interceptor's logger: a post-close dispatch must produce <strong>zero</strong>
 * log records on that handler.
 */
class DispatchAfterCloseShortCircuitsInterceptorsTest {

    record Beep(String x) {
    }

    private Logger           interceptorLogger;
    private RecordingHandler recordingHandler;
    private Level            previousLevel;

    @BeforeEach
    void attachJulRecorder() {
        // The LoggingDispatchInterceptor uses its own class' Logger,
        // not the root logger — we attach the recorder there so the
        // assertion is decoupled from the JUL global level.
        interceptorLogger = Logger.getLogger(LoggingDispatchInterceptor.class.getName());
        previousLevel     = interceptorLogger.getLevel();
        interceptorLogger.setLevel(Level.ALL);
        recordingHandler = new RecordingHandler();
        recordingHandler.setLevel(Level.ALL);
        interceptorLogger.addHandler(recordingHandler);
    }

    @AfterEach
    void detachJulRecorder() {
        interceptorLogger.removeHandler(recordingHandler);
        interceptorLogger.setLevel(previousLevel);
    }

    @Test
    void dispatchAfterClose_throwsIllegalStateException_withoutInvokingInterceptor() {
        FlowRuntime runtime =
                FlowRuntime.builder()
                        .interceptor(new LoggingDispatchInterceptor(System.Logger.Level.INFO))
                        .build();
        runtime.close();

        // The bus accessor itself short-circuits — the onion is never
        // constructed because runtime.commands() throws first.
        assertThrows(
                     IllegalStateException.class,
                     () -> runtime
                             .commands()
                             .dispatchAndReturnResult(
                                                      Command.<Beep>builder().body(new Beep("nope")).build(),
                                                      ExecutionContext.root(),
                                                      ErrorPolicy.failFast()));

        // Critical regression: the LoggingDispatchInterceptor MUST NOT
        // have been entered — otherwise it would have written a
        // "dispatching kind=COMMAND ..." record.
        assertTrue(
                   recordingHandler.records.isEmpty(),
                   " dispatch after close must NOT enter the interceptor "
                           + "onion. Captured records: "
                           + recordingHandler.records);
    }

    /**
     * Minimal JUL handler that buffers every record into a thread-safe list. Synchronisation is
     * provided by the JUL framework — JDK publishes records under a per-Logger lock — but we copy
     * into a defensive {@link ArrayList} to keep the assertion simple.
     */
    private static final class RecordingHandler extends Handler {
        final List<String> records = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(LogRecord record) {
            records.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
