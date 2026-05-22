package net.nexus_flow.core.eventsourcing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.Serial;
import java.util.UUID;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import org.junit.jupiter.api.Test;

/**
 * Verifies that recording an event MUST NOT bump the committed version. The version only advances
 * after {@code markCommitted} via {@code AggregateRepository.save}.
 */
class RecordEventDoesNotBumpCommittedVersionTest {

    static final class Item extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;
        private final UUID        id;

        Item(UUID id) {
            this.id = id;
        }

        @Override
        public UUID getAggregateId() {
            return id;
        }

        void touch() {
            recordEvent(new Touched(id.toString()));
        }
    }

    static final class Touched extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Touched(String aggId) {
            super(aggId);
        }
    }

    @Test
    void recording_doesNotBumpVersion_untilMarkCommitted() {
        Item it = new Item(UUID.randomUUID());
        assertEquals(0L, it.version());

        it.touch();
        assertEquals(0L, it.version(), "version stays at 0 after recordEvent");

        it.touch();
        it.touch();
        assertEquals(0L, it.version(), "version stays at 0 across multiple recordEvents");
        assertEquals(3, it.getUncommittedEvents().size());

        // Manual markCommitted (3 events, expected newVersion=3).
        it.markCommitted(3L);
        assertEquals(3L, it.version());
        assertEquals(0, it.getUncommittedEvents().size());
    }

    @Test
    void markCommitted_withWrongVersion_throws() {
        Item it = new Item(UUID.randomUUID());
        it.touch();
        // version=0, uncommittedCount=1, so newVersion=1 is the only legal call.
        org.junit.jupiter.api.Assertions.assertThrows(
                                                      IllegalArgumentException.class, () -> it.markCommitted(2L));
    }
}
