package net.nexus_flow.core.cqrs.command;

import static org.junit.jupiter.api.Assertions.*;

import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.types.TypeReference;
import org.junit.jupiter.api.Test;

/**
 * Validates the {@link CommandRegistrationSnapshot} facade as a read-only view of all registered
 * command handlers on a {@link CommandBus}.
 *
 * <p>The snapshot must:
 *
 * <ul>
 * <li>Be empty on a fresh runtime.
 * <li>Distinguish no-return vs. return command types.
 * <li>Decouple from the live registry: registering after the snapshot is taken must NOT mutate
 * the snapshot.
 * <li>Return immutable sets so callers cannot tamper with the view.
 * <li>Reflect {@code unregister} calls in subsequent snapshots.
 * </ul>
 */
class CommandRegistrationSnapshotTest {

    record DoVoid(String tag) {
    }

    record DoReturn(String tag) {
    }

    @Test
    void freshRuntime_hasEmptySnapshot() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            CommandRegistrationSnapshot snap = runtime.commands().registrationSnapshot();
            assertTrue(snap.isEmpty(), "fresh runtime must have empty snapshot");
            assertEquals(0, snap.size());
            assertTrue(snap.noReturnCommandTypes().isEmpty());
            assertTrue(snap.returnCommandTypes().isEmpty());
        }
    }

    @Test
    void snapshot_distinguishesNoReturnFromReturn() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var voidHandler   =
                    new AbstractNoReturnCommandHandler<DoVoid>() {
                                          @Override
                                          protected void handle(DoVoid command) {
                                                                                     /* no-op */
                                          }
                                      };
            var returnHandler =
                    new AbstractReturnCommandHandler<DoReturn, String>() {
                                          @Override
                                          protected String handle(DoReturn command) {
                                              return command.tag();
                                          }
                                      };
            runtime.commands().register(voidHandler);
            runtime.commands().register(returnHandler);

            CommandRegistrationSnapshot snap = runtime.commands().registrationSnapshot();
            assertEquals(2, snap.size(), "two distinct registrations");
            assertEquals(1, snap.noReturnCommandTypes().size());
            assertEquals(1, snap.returnCommandTypes().size());
            assertTrue(
                       snap.noReturnCommandTypes().stream()
                               .map(TypeReference::getType)
                               .map(Object::toString)
                               .anyMatch(s -> s.contains("DoVoid")));
            assertTrue(
                       snap.returnCommandTypes().stream()
                               .map(TypeReference::getType)
                               .map(Object::toString)
                               .anyMatch(s -> s.contains("DoReturn")));
        }
    }

    @Test
    void snapshot_isImmutable_andDecouplesFromSubsequentMutations() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var voidHandler =
                    new AbstractNoReturnCommandHandler<DoVoid>() {
                        @Override
                        protected void handle(DoVoid command) {
                            /* no-op */
                        }
                    };
            runtime.commands().register(voidHandler);
            CommandRegistrationSnapshot snapBefore = runtime.commands().registrationSnapshot();
            int                         sizeBefore = snapBefore.size();

            // mutate the registry after the snapshot is captured
            var returnHandler =
                    new AbstractReturnCommandHandler<DoReturn, String>() {
                        @Override
                        protected String handle(DoReturn command) {
                            return command.tag();
                        }
                    };
            runtime.commands().register(returnHandler);

            assertEquals(
                         sizeBefore,
                         snapBefore.size(),
                         "first snapshot must not see registrations made after it was taken");
            assertEquals(
                         sizeBefore + 1,
                         runtime.commands().registrationSnapshot().size(),
                         "second snapshot must reflect the live registration");

            // returned sets are immutable
            assertThrows(
                         UnsupportedOperationException.class,
                         () -> snapBefore.noReturnCommandTypes().clear(),
                         "noReturnCommandTypes must be immutable");
            assertThrows(
                         UnsupportedOperationException.class,
                         () -> snapBefore.returnCommandTypes().clear(),
                         "returnCommandTypes must be immutable");
        }
    }

    @Test
    void duplicateRegistration_forSameCommandTypeIsRejected() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var first  =
                    new AbstractNoReturnCommandHandler<DoVoid>() {
                                   @Override
                                   protected void handle(DoVoid command) {
                                                                            /* no-op */
                                   }
                               };
            var second =
                    new AbstractReturnCommandHandler<DoVoid, String>() {
                                   @Override
                                   protected String handle(DoVoid command) {
                                       return command.tag();
                                   }
                               };
            runtime.commands().register(first);

            IllegalArgumentException error =
                    assertThrows(IllegalArgumentException.class, () -> runtime.commands().register(second));

            assertTrue(
                       error.getMessage().contains("DoVoid"),
                       "duplicate-registration error must name the conflicting command type");
            assertEquals(
                         1,
                         runtime.commands().registrationSnapshot().size(),
                         "failed duplicate registration must not mutate the registry");
        }
    }

    @Test
    void snapshot_reflectsUnregister() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            var voidHandler =
                    new AbstractNoReturnCommandHandler<DoVoid>() {
                        @Override
                        protected void handle(DoVoid command) {
                            /* no-op */
                        }
                    };
            runtime.commands().register(voidHandler);
            assertEquals(1, runtime.commands().registrationSnapshot().size());
            runtime.commands().unregister(voidHandler);
            assertTrue(
                       runtime.commands().registrationSnapshot().isEmpty(),
                       "snapshot taken after unregister must be empty");
        }
    }
}
