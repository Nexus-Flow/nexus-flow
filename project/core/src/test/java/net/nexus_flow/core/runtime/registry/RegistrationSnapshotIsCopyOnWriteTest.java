package net.nexus_flow.core.runtime.registry;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * {@link RegistrationSnapshot} is copy-on-write: every mutating operation returns a <em>new</em>
 * snapshot and never mutates the receiver. The list inside each snapshot is itself unmodifiable.
 * This is the precondition that lets {@link HandlerRegistry} hold the current snapshot in a {@code
 * volatile} field and publish atomically.
 */
class RegistrationSnapshotIsCopyOnWriteTest {

    static final class Msg {
    }

    private static HandlerInvoker<Object, String> labelled(String label) {
        return (m, ctx) -> label;
    }

    @Test
    void withRegistration_returnsNewSnapshot_andLeavesReceiverUntouched() {
        RegistrationSnapshot a = RegistrationSnapshot.EMPTY;
        RegistrationSnapshot b = a.withRegistration(Msg.class, /* order= */ 0, labelled("x"));

        assertNotSame(a, b);
        assertEquals(
                     0,
                     a.handlersFor(Msg.class).size(),
                     "EMPTY snapshot must remain empty after a withRegistration call");
        assertEquals(1, b.handlersFor(Msg.class).size());
    }

    @Test
    void handlersFor_returnsUnmodifiableList_whichRejectsExternalMutation() {
        RegistrationSnapshot s    =
                RegistrationSnapshot.EMPTY.withRegistration(Msg.class, 0, labelled("only"));
        var                  list = s.handlersFor(Msg.class);
        assertThrows(UnsupportedOperationException.class, () -> list.add(null));
        assertThrows(UnsupportedOperationException.class, () -> list.removeFirst());
    }

    @Test
    void withoutType_returnsSameInstance_whenTypeIsAbsent() {
        // Microscopic but important: avoids cache invalidation churn
        // when unregistering an unknown type.
        RegistrationSnapshot a = RegistrationSnapshot.EMPTY;
        RegistrationSnapshot b = a.withoutType(Msg.class);
        // Same identity (no copy when there's nothing to remove).
        assertEquals(a, b);
        assertSame(a, b);
    }

    @Test
    void sequenceNumbers_areGlobalAndMonotonic_acrossTypes() {
        RegistrationSnapshot s =
                RegistrationSnapshot.EMPTY
                        .withRegistration(Msg.class, 0, labelled("a"))
                        .withRegistration(Object.class, 0, labelled("b"))
                        .withRegistration(Msg.class, 0, labelled("c"));
        // Sequence on each handler is the snapshot's nextSequence at
        // the time of insertion; the global counter advances per
        // registration regardless of type. We assert the sequences
        // are strictly increasing across all entries.
        var msgList = s.handlersFor(Msg.class);
        assertEquals(2, msgList.size());
        // sequences for Msg.class registrations were 0 and 2 (1 went
        // to Object.class).
        assertEquals(0, msgList.get(0).sequence());
        assertEquals(2, msgList.get(1).sequence());
    }
}
