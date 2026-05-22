package net.nexus_flow.core.eventsourcing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import org.junit.jupiter.api.Test;

/**
 * Verifies a {@link ProjectionRunner} starting with a saved checkpoint=N applies only envelopes
 * with globalPosition > N.
 */
class ProjectionResumesFromCheckpointTest {

    static final class Marker extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Marker(String aggId) {
            super(aggId);
        }
    }

    static final class CollectingProjection implements Projection {
        final List<Long> positions = new ArrayList<>();
        long             checkpoint;

        @Override
        public String name() {
            return "collecting";
        }

        @Override
        public void apply(EventEnvelope envelope) {
            positions.add(envelope.globalPosition());
            checkpoint = envelope.globalPosition();
        }

        @Override
        public long checkpoint() {
            return checkpoint;
        }
    }

    @Test
    void runner_skips_envelopesAtOrBelow_checkpoint() {
        EventStore store = new InMemoryEventStore();
        UUID       id    = UUID.randomUUID();
        StreamId   s     = new StreamId("test.A", id);
        // Seed 15 envelopes.
        for (int i = 0; i < 15; i++) {
            store.append(s, i, List.of(new Marker(id.toString())));
        }

        // Pre-seed a checkpoint of 10.
        ProjectionCheckpointStore checkpoints = new InMemoryProjectionCheckpointStore();
        checkpoints.save("collecting", 10L);

        CollectingProjection projection = new CollectingProjection();
        ProjectionRunner     runner     = new ProjectionRunner(store, checkpoints, projection);
        long                 applied    = runner.catchUp();

        // Only envelopes 11..15 are seen.
        assertEquals(5L, applied);
        assertEquals(5, projection.positions.size());
        assertEquals(11L, projection.positions.get(0));
        assertEquals(15L, projection.positions.get(4));
        // Strict monotonic.
        for (int i = 1; i < projection.positions.size(); i++) {
            assertTrue(projection.positions.get(i) > projection.positions.get(i - 1));
        }
        // Checkpoint advanced to 15.
        assertEquals(15L, checkpoints.load("collecting"));
    }
}
