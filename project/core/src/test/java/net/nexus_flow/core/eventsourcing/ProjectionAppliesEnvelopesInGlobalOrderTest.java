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
 * Verifies a projection consuming {@link EventStore#readAll(long, long)} observes envelopes in
 * monotonic global-position order regardless of which stream they belong to.
 */
class ProjectionAppliesEnvelopesInGlobalOrderTest {

    static final class Tag extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;
        private final String      name;

        Tag(String aggId, String tag) {
            super(aggId);
            this.name = tag;
        }

        String tag() {
            return name;
        }
    }

    static final class TagCollector implements Projection {
        final List<String> seen      = new ArrayList<>();
        final List<Long>   positions = new ArrayList<>();
        long               checkpoint;

        @Override
        public String name() {
            return "tag-collector";
        }

        @Override
        public void apply(EventEnvelope envelope) {
            seen.add(((Tag) envelope.payload()).tag());
            positions.add(envelope.globalPosition());
            checkpoint = envelope.globalPosition();
        }

        @Override
        public long checkpoint() {
            return checkpoint;
        }
    }

    @Test
    void interleaved_appends_projection_seesGlobalOrder() {
        EventStore store = new InMemoryEventStore();
        UUID       a     = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        StreamId   sa    = new StreamId("test.A", a);
        StreamId   sb    = new StreamId("test.B", b);
        StreamId   sc    = new StreamId("test.C", c);

        store.append(sa, 0L, List.of(new Tag(a.toString(), "a1")));
        store.append(sb, 0L, List.of(new Tag(b.toString(), "b1")));
        store.append(sc, 0L, List.of(new Tag(c.toString(), "c1")));
        store.append(sa, 1L, List.of(new Tag(a.toString(), "a2")));
        store.append(sb, 1L, List.of(new Tag(b.toString(), "b2")));

        TagCollector              projection  = new TagCollector();
        ProjectionCheckpointStore checkpoints = new InMemoryProjectionCheckpointStore();
        ProjectionRunner          runner      = new ProjectionRunner(store, checkpoints, projection);

        long applied = runner.catchUp();
        assertEquals(5L, applied);
        assertEquals(List.of("a1", "b1", "c1", "a2", "b2"), projection.seen);
        // Positions strictly monotonic.
        for (int i = 1; i < projection.positions.size(); i++) {
            assertTrue(projection.positions.get(i) > projection.positions.get(i - 1));
        }
        assertEquals(projection.positions.getLast(), checkpoints.load(projection.name()));
    }
}
