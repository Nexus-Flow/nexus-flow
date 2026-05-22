package net.nexus_flow.core.eventsourcing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Verifies repeated starts share the same lifecycle future until the runner is closed. */
class ProjectionRunnerStartReturnsSameFutureTest {

    static final class NoopProjection implements Projection {
        private long checkpoint;

        @Override
        public String name() {
            return "noop";
        }

        @Override
        public void apply(EventEnvelope envelope) {
            checkpoint = envelope.globalPosition();
        }

        @Override
        public long checkpoint() {
            return checkpoint;
        }
    }

    @Test
    void start_twice_returnsSameFuture_untilClosed() throws Exception {
        ProjectionRunner runner =
                new ProjectionRunner(
                        new InMemoryEventStore(),
                        new InMemoryProjectionCheckpointStore(),
                        new NoopProjection(),
                        8L,
                        Duration.ofMillis(10));

        CompletableFuture<Void> first  = runner.start();
        CompletableFuture<Void> second = runner.start();

        assertSame(first, second);
        assertFalse(first.isDone());

        runner.close();

        first.get(2, TimeUnit.SECONDS);
        assertTrue(first.isDone());
    }
}
