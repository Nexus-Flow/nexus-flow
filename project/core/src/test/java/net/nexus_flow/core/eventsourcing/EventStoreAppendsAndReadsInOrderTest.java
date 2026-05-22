package net.nexus_flow.core.eventsourcing;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.List;
import java.util.UUID;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.DomainEvent;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link EventStore} append-then-read invariants: appends produce strictly monotonic
 * streamVersion and globalPosition.
 */
class EventStoreAppendsAndReadsInOrderTest {

    static final class TestEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;
        private final String      tag;

        TestEvent(String aggId, String tag) {
            super(aggId);
            this.tag = tag;
        }

        String tag() {
            return tag;
        }
    }

    @Test
    void appends_three_events_readReturnsInOrder() {
        EventStore store  = new InMemoryEventStore();
        UUID       aggId  = UUID.randomUUID();
        StreamId   stream = new StreamId("test.Aggregate", aggId);

        DomainEvent e1 = new TestEvent(aggId.toString(), "a");
        DomainEvent e2 = new TestEvent(aggId.toString(), "b");
        DomainEvent e3 = new TestEvent(aggId.toString(), "c");

        AppendResult         r       = store.append(stream, 0L, List.of(e1, e2, e3));
        AppendResult.Success success = assertInstanceOf(AppendResult.Success.class, r);
        assertEquals(3L, success.newVersion());
        assertEquals(1L, success.firstGlobalPosition());

        EventStream slice = store.read(stream, 1L, 100L);
        assertEquals(3, slice.size());
        assertEquals(3L, slice.lastVersion());

        EventEnvelope env1 = slice.events().get(0);
        EventEnvelope env2 = slice.events().get(1);
        EventEnvelope env3 = slice.events().get(2);

        assertEquals(1L, env1.streamVersion());
        assertEquals(2L, env2.streamVersion());
        assertEquals(3L, env3.streamVersion());

        // Global positions strictly monotonic
        assertEquals(env1.globalPosition() + 1, env2.globalPosition());
        assertEquals(env2.globalPosition() + 1, env3.globalPosition());

        assertNotNull(env1.messageId());
        assertEquals("a", ((TestEvent) env1.payload()).tag());
        assertEquals("b", ((TestEvent) env2.payload()).tag());
        assertEquals("c", ((TestEvent) env3.payload()).tag());
    }

    @Test
    void read_unknownStream_returnsEmpty() {
        EventStore  store   = new InMemoryEventStore();
        StreamId    unknown = new StreamId("test.Aggregate", UUID.randomUUID());
        EventStream slice   = store.read(unknown, 1L, 100L);
        assertEquals(0, slice.size());
        assertEquals(0L, slice.lastVersion());
    }

    @Test
    void incremental_append_respectsVersion() {
        EventStore store  = new InMemoryEventStore();
        UUID       aggId  = UUID.randomUUID();
        StreamId   stream = new StreamId("test.Aggregate", aggId);

        store.append(stream, 0L, List.of(new TestEvent(aggId.toString(), "a")));
        AppendResult         r       = store.append(stream, 1L, List.of(new TestEvent(aggId.toString(), "b")));
        AppendResult.Success success = assertInstanceOf(AppendResult.Success.class, r);
        assertEquals(2L, success.newVersion());
        assertEquals(2L, success.firstGlobalPosition());

        EventStream slice = store.read(stream, 1L, 100L);
        assertEquals(2, slice.size());
    }
}
