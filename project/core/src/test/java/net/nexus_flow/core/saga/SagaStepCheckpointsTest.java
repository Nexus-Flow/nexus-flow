package net.nexus_flow.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the typed {@link StepCheckpoint} field on {@link SagaState}:
 *
 * <ol>
 * <li>Backwards-compat constructors (8-arg, 9-arg) preserve the no-checkpoints behaviour
 * by delegating with an empty map.
 * <li>{@link SagaState#withStepCheckpoint(StepCheckpoint)} adds the entry and preserves
 * everything else; subsequent calls REPLACE same-name entries (latest wins).
 * <li>{@link SagaState#hasStep(String)} returns true once a checkpoint exists for that
 * step name.
 * <li>{@link SagaState#next(Map, SagaStatus, long, Instant)} preserves the checkpoint map
 * — step completions survive every regular state transition.
 * <li>{@link StepCheckpoint} compact constructor rejects blank step name, blank outcome,
 * null fields.
 * </ol>
 */
class SagaStepCheckpointsTest {

    private static final Instant T0 = Instant.parse("2026-05-28T12:00:00Z");

    @Test
    void fresh_hasNoCheckpoints_and_hasStepReturnsFalse() {
        SagaState state = SagaState.fresh(SagaId.random(), "Order", T0);
        assertTrue(state.stepCheckpoints().isEmpty());
        assertFalse(state.hasStep("ANY"));
        assertNull(state.stepCheckpoint("ANY"));
    }

    @Test
    void backCompat_8argConstructor_initialisesEmptyCheckpoints() {
        SagaState state = new SagaState(
                SagaId.random(), "Order", SagaStatus.RUNNING, 0L, Map.of(), T0, T0, 0L);
        assertTrue(state.stepCheckpoints().isEmpty(),
                   "the no-checkpoints constructor MUST seed with Map.of()");
    }

    @Test
    void backCompat_9argConstructor_initialisesEmptyCheckpoints() {
        SagaState state = new SagaState(
                SagaId.random(), "Order", SagaStatus.RUNNING, 0L, Map.of(), T0, T0, 0L, null);
        assertTrue(state.stepCheckpoints().isEmpty());
    }

    @Test
    void withStepCheckpoint_addsEntry_andPreservesEverythingElse() {
        SagaState      before   = SagaState.fresh(SagaId.random(), "Order", T0);
        StepCheckpoint reserved = StepCheckpoint.success("RESERVE_INVENTORY", T0.plusSeconds(5));
        SagaState      after    = before.withStepCheckpoint(reserved);

        assertEquals(1, after.stepCheckpoints().size());
        assertTrue(after.hasStep("RESERVE_INVENTORY"));
        assertEquals(reserved, after.stepCheckpoint("RESERVE_INVENTORY"));
        // Other fields preserved unchanged.
        assertEquals(before.id(), after.id());
        assertEquals(before.type(), after.type());
        assertEquals(before.version(), after.version());
        // Original unaffected (immutable).
        assertFalse(before.hasStep("RESERVE_INVENTORY"));
    }

    @Test
    void withStepCheckpoint_sameName_replacesPreviousEntry() {
        SagaState      state    = SagaState.fresh(SagaId.random(), "Order", T0)
                .withStepCheckpoint(StepCheckpoint.success("CHARGE", T0));
        StepCheckpoint newer    = new StepCheckpoint(
                "CHARGE", T0.plusSeconds(10), StepCheckpoint.OUTCOME_COMPENSATED,
                Map.of("refundId", "rf-7"));
        SagaState      replaced = state.withStepCheckpoint(newer);
        assertEquals(1, replaced.stepCheckpoints().size(),
                     "re-checkpointing the same step name MUST replace, not duplicate");
        assertEquals(StepCheckpoint.OUTCOME_COMPENSATED,
                     replaced.stepCheckpoint("CHARGE").outcome());
    }

    @Test
    void next_preservesCheckpointsAcrossTransition() {
        SagaState start    = SagaState.fresh(SagaId.random(), "Order", T0)
                .withStepCheckpoint(StepCheckpoint.success("RESERVE", T0))
                .withStepCheckpoint(StepCheckpoint.success("CHARGE", T0.plusSeconds(1)));
        SagaState advanced = start.next(
                                        Map.of(), SagaStatus.RUNNING, 1L, T0.plusSeconds(2));
        assertEquals(2, advanced.stepCheckpoints().size(),
                     "next() MUST preserve every prior step checkpoint");
        assertTrue(advanced.hasStep("RESERVE"));
        assertTrue(advanced.hasStep("CHARGE"));
        assertNotSame(start, advanced);
    }

    @Test
    void withStepCheckpoints_replacesEntireMap() {
        SagaState                   start    = SagaState.fresh(SagaId.random(), "Order", T0)
                .withStepCheckpoint(StepCheckpoint.success("RESERVE", T0));
        Map<String, StepCheckpoint> migrated = Map.of(
                                                      "RESERVE", StepCheckpoint.success("RESERVE", T0),
                                                      "CHARGE", StepCheckpoint.success("CHARGE", T0.plusSeconds(1)));
        SagaState                   swapped  = start.withStepCheckpoints(migrated);
        assertEquals(2, swapped.stepCheckpoints().size());
        assertTrue(swapped.hasStep("CHARGE"));
    }

    @Test
    void stepCheckpoint_compactConstructor_rejectsBlankAndNull() {
        assertThrows(NullPointerException.class,
                     () -> new StepCheckpoint(null, T0, "OK", Map.of()));
        assertThrows(IllegalArgumentException.class,
                     () -> new StepCheckpoint("", T0, "OK", Map.of()));
        assertThrows(NullPointerException.class,
                     () -> new StepCheckpoint("X", null, "OK", Map.of()));
        assertThrows(NullPointerException.class,
                     () -> new StepCheckpoint("X", T0, null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                     () -> new StepCheckpoint("X", T0, "  ", Map.of()));
        assertThrows(NullPointerException.class,
                     () -> new StepCheckpoint("X", T0, "OK", null));
    }

    @Test
    void stepCheckpoint_attribute_returnsValue_orNullForMissingKey() {
        StepCheckpoint cp = new StepCheckpoint(
                "RESERVE", T0, StepCheckpoint.OUTCOME_SUCCESS, Map.of("foo", "bar"));
        assertEquals("bar", cp.attribute("foo"));
        assertNull(cp.attribute("missing"));
    }

    @Test
    void stepCheckpoint_attributes_areImmutable() {
        java.util.Map<String, String> mutable = new java.util.HashMap<>();
        mutable.put("a", "1");
        StepCheckpoint cp = new StepCheckpoint("X", T0, "OK", mutable);
        mutable.put("b", "2"); // mutate after construction
        assertFalse(cp.attributes().containsKey("b"),
                    "compact constructor MUST defensively copy the attributes map");
    }
}
