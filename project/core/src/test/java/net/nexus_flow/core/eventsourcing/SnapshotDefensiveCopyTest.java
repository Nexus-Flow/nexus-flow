package net.nexus_flow.core.eventsourcing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies snapshot state is not exposed as mutable shared storage. */
class SnapshotDefensiveCopyTest {

    @Test
    void snapshot_stateAccessor_returnsDefensiveCopy() {
        byte[]   original = new byte[]{1, 2, 3};
        Snapshot snapshot =
                new Snapshot(new StreamId("test.Aggregate", UUID.randomUUID()), 7L, original, "test/bin");

        original[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, snapshot.state());

        byte[] leaked = snapshot.state();
        leaked[1] = 8;
        assertArrayEquals(new byte[]{1, 2, 3}, snapshot.state());
    }

    @Test
    void inMemoryStore_load_doesNotExposeMutableSnapshotState() {
        InMemorySnapshotStore store  = new InMemorySnapshotStore();
        StreamId              stream = new StreamId("test.Aggregate", UUID.randomUUID());
        store.save(new Snapshot(stream, 3L, new byte[]{4, 5, 6}, "test/bin"));

        Snapshot loaded = store.load(stream).orElseThrow();
        byte[]   leaked = loaded.state();
        leaked[2] = 0;

        assertTrue(store.load(stream).isPresent());
        assertArrayEquals(new byte[]{4, 5, 6}, store.load(stream).orElseThrow().state());
    }
}
