package net.nexus_flow.core.ring.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.nexus_flow.core.ring.transport.PeerId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins {@link PersistedRingStateStore} read/write round-trip + safety guarantees: atomic
 * writes, deterministic byte-equal output for the same logical state, header version
 * enforcement, malformed-line rejection.
 */
class PersistedRingStateStoreTest {

    @Test
    void roundTrip_preservesEveryEntry(@TempDir Path dir) throws IOException {
        PersistedRingStateStore store    = new PersistedRingStateStore(dir.resolve("state.txt"));
        Map<PeerId, Long>       original = new LinkedHashMap<>();
        original.put(PeerId.of("alpha"), 42L);
        original.put(PeerId.of("beta"), 1024L);
        original.put(PeerId.of("gamma"), 0L);
        store.write(original);
        Map<PeerId, Long> decoded = store.read();
        assertEquals(original, decoded);
    }

    @Test
    void read_missingFile_returnsEmptyMap(@TempDir Path dir) throws IOException {
        PersistedRingStateStore store = new PersistedRingStateStore(dir.resolve("absent.txt"));
        assertTrue(store.read().isEmpty());
    }

    @Test
    void write_isAtomic_replacesPriorContent(@TempDir Path dir) throws IOException {
        Path                    path  = dir.resolve("state.txt");
        PersistedRingStateStore store = new PersistedRingStateStore(path);
        store.write(Map.of(PeerId.of("alpha"), 1L));
        store.write(Map.of(PeerId.of("beta"), 2L));
        Map<PeerId, Long> result = store.read();
        assertEquals(1, result.size());
        assertEquals(2L, result.get(PeerId.of("beta")));
    }

    @Test
    void write_outputIsDeterministic_acrossInsertionOrders(@TempDir Path dir) throws IOException {
        // Two different insertion orders of the same logical state must produce byte-equal files.
        Map<PeerId, Long> orderA = new LinkedHashMap<>();
        orderA.put(PeerId.of("z"), 10L);
        orderA.put(PeerId.of("a"), 20L);
        Map<PeerId, Long> orderB = new LinkedHashMap<>();
        orderB.put(PeerId.of("a"), 20L);
        orderB.put(PeerId.of("z"), 10L);
        PersistedRingStateStore storeA = new PersistedRingStateStore(dir.resolve("a.txt"));
        PersistedRingStateStore storeB = new PersistedRingStateStore(dir.resolve("b.txt"));
        storeA.write(orderA);
        storeB.write(orderB);
        byte[] bytesA = Files.readAllBytes(storeA.path());
        byte[] bytesB = Files.readAllBytes(storeB.path());
        assertEquals(new String(bytesA, StandardCharsets.UTF_8), new String(bytesB, StandardCharsets.UTF_8));
    }

    @Test
    void read_missingHeader_throws(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("state.txt");
        Files.writeString(path, "alpha\t42\n", StandardCharsets.UTF_8);
        PersistedRingStateStore store = new PersistedRingStateStore(path);
        IllegalStateException   ex    = assertThrows(IllegalStateException.class, store::read);
        assertTrue(ex.getMessage().contains("missing or wrong header"), ex.getMessage());
    }

    @Test
    void read_wrongVersion_throws(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("state.txt");
        Files.writeString(
                          path,
                          "# nexus-flow ring state v99\nalpha\t42\n",
                          StandardCharsets.UTF_8);
        PersistedRingStateStore store = new PersistedRingStateStore(path);
        assertThrows(IllegalStateException.class, store::read);
    }

    @Test
    void read_malformedLine_throws(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("state.txt");
        Files.writeString(
                          path,
                          PersistedRingStateStore.HEADER + "\nno-tab-separator\n",
                          StandardCharsets.UTF_8);
        PersistedRingStateStore store = new PersistedRingStateStore(path);
        IllegalStateException   ex    = assertThrows(IllegalStateException.class, store::read);
        assertTrue(ex.getMessage().contains("malformed"), ex.getMessage());
    }

    @Test
    void read_nonNumericSequence_throws(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("state.txt");
        Files.writeString(
                          path,
                          PersistedRingStateStore.HEADER + "\nalpha\tnot-a-number\n",
                          StandardCharsets.UTF_8);
        PersistedRingStateStore store = new PersistedRingStateStore(path);
        assertThrows(IllegalStateException.class, store::read);
    }

    @Test
    void read_negativeSequence_throws(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("state.txt");
        Files.writeString(
                          path,
                          PersistedRingStateStore.HEADER + "\nalpha\t-1\n",
                          StandardCharsets.UTF_8);
        PersistedRingStateStore store = new PersistedRingStateStore(path);
        assertThrows(IllegalStateException.class, store::read);
    }

    @Test
    void read_blankLinesAndComments_areSkipped(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("state.txt");
        Files.writeString(
                          path,
                          PersistedRingStateStore.HEADER
                                  + "\n\n# a comment\nalpha\t42\n\n# another\nbeta\t99\n",
                          StandardCharsets.UTF_8);
        PersistedRingStateStore store  = new PersistedRingStateStore(path);
        Map<PeerId, Long>       result = store.read();
        assertEquals(2, result.size());
        assertEquals(42L, result.get(PeerId.of("alpha")));
        assertEquals(99L, result.get(PeerId.of("beta")));
    }

    @Test
    void write_rejectsNegativeSequence(@TempDir Path dir) {
        PersistedRingStateStore store = new PersistedRingStateStore(dir.resolve("s.txt"));
        assertThrows(
                     IllegalArgumentException.class,
                     () -> store.write(Map.of(PeerId.of("a"), -1L)));
    }
}
