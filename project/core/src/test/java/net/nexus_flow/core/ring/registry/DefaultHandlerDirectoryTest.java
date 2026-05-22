package net.nexus_flow.core.ring.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.nexus_flow.core.ring.transport.PeerId;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link DefaultHandlerDirectory} read/write semantics. The directory is the
 * load-bearing index for cross-pod command/query routing; correctness here is what stops
 * dispatches from being silently dropped because a stale peer is still in the candidate set.
 */
class DefaultHandlerDirectoryTest {

    private static final PeerId ALPHA = PeerId.of("alpha");
    private static final PeerId BETA  = PeerId.of("beta");
    private static final PeerId GAMMA = PeerId.of("gamma");

    @Test
    void register_addsPeerToTypeSet() {
        DefaultHandlerDirectory dir = new DefaultHandlerDirectory();
        dir.register(HandlerRole.COMMAND, ALPHA, List.of("X", "Y"));
        assertEquals(Set.of(ALPHA), dir.whoHandles(HandlerRole.COMMAND, "X"));
        assertEquals(Set.of(ALPHA), dir.whoHandles(HandlerRole.COMMAND, "Y"));
    }

    @Test
    void register_multiplePeersForSameType_returnsAllInSnapshot() {
        DefaultHandlerDirectory dir = new DefaultHandlerDirectory();
        dir.register(HandlerRole.COMMAND, ALPHA, List.of("X"));
        dir.register(HandlerRole.COMMAND, BETA, List.of("X"));
        dir.register(HandlerRole.COMMAND, GAMMA, List.of("X"));
        assertEquals(Set.of(ALPHA, BETA, GAMMA), dir.whoHandles(HandlerRole.COMMAND, "X"));
    }

    @Test
    void register_isIdempotentForSamePeerSameTypes() {
        DefaultHandlerDirectory dir = new DefaultHandlerDirectory();
        dir.register(HandlerRole.COMMAND, ALPHA, List.of("X"));
        dir.register(HandlerRole.COMMAND, ALPHA, List.of("X"));
        assertEquals(Set.of(ALPHA), dir.whoHandles(HandlerRole.COMMAND, "X"));
    }

    @Test
    void register_replacesPriorAdvertisement_forSamePeerSameRole() {
        DefaultHandlerDirectory dir = new DefaultHandlerDirectory();
        dir.register(HandlerRole.COMMAND, ALPHA, List.of("X", "Y"));
        // ALPHA now only handles Z — its prior X/Y advertisements should be dropped.
        dir.register(HandlerRole.COMMAND, ALPHA, List.of("Z"));
        assertTrue(dir.whoHandles(HandlerRole.COMMAND, "X").isEmpty());
        assertTrue(dir.whoHandles(HandlerRole.COMMAND, "Y").isEmpty());
        assertEquals(Set.of(ALPHA), dir.whoHandles(HandlerRole.COMMAND, "Z"));
    }

    @Test
    void unregister_removesEveryAdvertisementForPeerInRole() {
        DefaultHandlerDirectory dir = new DefaultHandlerDirectory();
        dir.register(HandlerRole.COMMAND, ALPHA, List.of("X", "Y"));
        dir.register(HandlerRole.COMMAND, BETA, List.of("X"));
        dir.unregister(HandlerRole.COMMAND, ALPHA);
        assertEquals(Set.of(BETA), dir.whoHandles(HandlerRole.COMMAND, "X"));
        assertTrue(dir.whoHandles(HandlerRole.COMMAND, "Y").isEmpty());
    }

    @Test
    void unregister_removesEntryEntirelyWhenLastPeerGone() {
        DefaultHandlerDirectory dir = new DefaultHandlerDirectory();
        dir.register(HandlerRole.COMMAND, ALPHA, List.of("X"));
        dir.unregister(HandlerRole.COMMAND, ALPHA);
        assertTrue(dir.whoHandles(HandlerRole.COMMAND, "X").isEmpty());
        assertTrue(dir.typesHandled(HandlerRole.COMMAND).isEmpty(),
                   "type with zero handlers MUST NOT appear in typesHandled() — would mislead callers");
    }

    @Test
    void commandAndQueryRoles_areIndependent() {
        DefaultHandlerDirectory dir = new DefaultHandlerDirectory();
        dir.register(HandlerRole.COMMAND, ALPHA, List.of("X"));
        dir.register(HandlerRole.QUERY, BETA, List.of("X"));
        assertEquals(Set.of(ALPHA), dir.whoHandles(HandlerRole.COMMAND, "X"));
        assertEquals(Set.of(BETA), dir.whoHandles(HandlerRole.QUERY, "X"));
    }

    @Test
    void unregisterFromAllRoles_removesAcrossBoth() {
        DefaultHandlerDirectory dir = new DefaultHandlerDirectory();
        dir.register(HandlerRole.COMMAND, ALPHA, List.of("X"));
        dir.register(HandlerRole.QUERY, ALPHA, List.of("Y"));
        dir.unregisterFromAllRoles(ALPHA);
        assertTrue(dir.whoHandles(HandlerRole.COMMAND, "X").isEmpty());
        assertTrue(dir.whoHandles(HandlerRole.QUERY, "Y").isEmpty());
    }

    @Test
    void whoHandles_unknownType_returnsEmpty() {
        DefaultHandlerDirectory dir = new DefaultHandlerDirectory();
        assertTrue(dir.whoHandles(HandlerRole.COMMAND, "Nope").isEmpty());
    }

    @Test
    void register_nullArgs_throw() {
        DefaultHandlerDirectory dir = new DefaultHandlerDirectory();
        assertThrows(NullPointerException.class, () -> dir.register(null, ALPHA, List.of("X")));
        assertThrows(
                     NullPointerException.class,
                     () -> dir.register(HandlerRole.COMMAND, null, List.of("X")));
        assertThrows(
                     NullPointerException.class,
                     () -> dir.register(HandlerRole.COMMAND, ALPHA, null));
    }
}
