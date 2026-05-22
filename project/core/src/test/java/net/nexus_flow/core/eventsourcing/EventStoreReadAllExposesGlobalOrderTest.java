package net.nexus_flow.core.eventsourcing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.util.List;
import java.util.UUID;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link EventStore#readAll(long, long)} exposes envelopes in global-position order
 * regardless of which stream they belong to.
 */
class EventStoreReadAllExposesGlobalOrderTest {

    static final class Ev extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;
        private final String      tag;

        Ev(String aggId, String tag) {
            super(aggId);
            this.tag = tag;
        }

        String tag() {
            return tag;
        }
    }

    @Test
    void interleaved_appends_readAll_returnsGlobalOrder() {
        EventStore store = new InMemoryEventStore();
        UUID       a     = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        StreamId   sa    = new StreamId("test.A", a);
        StreamId   sb    = new StreamId("test.B", b);
        StreamId   sc    = new StreamId("test.C", c);

        // Interleaved
        store.append(sa, 0L, List.of(new Ev(a.toString(), "a1")));
        store.append(sb, 0L, List.of(new Ev(b.toString(), "b1")));
        store.append(sa, 1L, List.of(new Ev(a.toString(), "a2")));
        store.append(sc, 0L, List.of(new Ev(c.toString(), "c1")));
        store.append(sb, 1L, List.of(new Ev(b.toString(), "b2")));
        store.append(sa, 2L, List.of(new Ev(a.toString(), "a3")));

        EventStream all = store.readAll(1L, 100L);
        assertEquals(6, all.size());

        // Global positions strictly monotonic 1..6
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i + 1L, all.events().get(i).globalPosition());
        }

        // Recording order preserved across streams
        String[] expected = {"a1", "b1", "a2", "c1", "b2", "a3"};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], ((Ev) all.events().get(i).payload()).tag());
        }
    }

    @Test
    void readAll_paginates_byGlobalPosition() {
        EventStore store = new InMemoryEventStore();
        UUID       a     = UUID.randomUUID();
        StreamId   sa    = new StreamId("test.A", a);
        for (int i = 0; i < 10; i++) {
            store.append(sa, i, List.of(new Ev(a.toString(), "e" + i)));
        }
        EventStream firstPage  = store.readAll(1L, 3L);
        EventStream secondPage = store.readAll(4L, 3L);
        assertEquals(3, firstPage.size());
        assertEquals(3, secondPage.size());
        assertEquals(1L, firstPage.events().getFirst().globalPosition());
        assertEquals(4L, secondPage.events().getFirst().globalPosition());
        assertTrue(
                   firstPage.events().get(2).globalPosition() < secondPage.events().get(0).globalPosition());
    }
}
