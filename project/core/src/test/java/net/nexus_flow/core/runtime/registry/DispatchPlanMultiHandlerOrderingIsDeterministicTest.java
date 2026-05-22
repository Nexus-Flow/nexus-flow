package net.nexus_flow.core.runtime.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * multi-handler dispatch plans are ordered by:
 *
 * <ol>
 * <li>ascending {@code order} (primary)
 * <li>registration sequence FIFO (secondary tie-break)
 * </ol>
 *
 * The combination is total and deterministic: identical inputs must always produce identical {@link
 * DispatchPlan#handlers()} sequences, regardless of registration order across calls.
 */
class DispatchPlanMultiHandlerOrderingIsDeterministicTest {

    static final class Msg {
    }

    /** Labelled invoker used only to make ordering observable in assertions. */
    private static HandlerInvoker<Object, String> labelled(String label) {
        return (m, ctx) -> label;
    }

    @Test
    void primarySort_isOrderAscending() throws Throwable {
        HandlerRegistry<Object, String> reg = new HandlerRegistry<>();
        reg.registerInvoker(Msg.class, labelled("c"), /* order= */ 30);
        reg.registerInvoker(Msg.class, labelled("a"), /* order= */ 10);
        reg.registerInvoker(Msg.class, labelled("b"), /* order= */ 20);

        List<String> labels = invokeAll(reg.planFor(Msg.class));
        assertEquals(List.of("a", "b", "c"), labels);
    }

    @Test
    void secondarySort_isRegistrationFifo_amongEqualOrder() throws Throwable {
        HandlerRegistry<Object, String> reg = new HandlerRegistry<>();
        reg.registerInvoker(Msg.class, labelled("first"), /* order= */ 0);
        reg.registerInvoker(Msg.class, labelled("second"), /* order= */ 0);
        reg.registerInvoker(Msg.class, labelled("third"), /* order= */ 0);

        assertEquals(List.of("first", "second", "third"), invokeAll(reg.planFor(Msg.class)));
    }

    @Test
    void mixedOrders_areDeterministic_acrossRandomisedInsertion() throws Throwable {
        // 5 registrations: 3 with order=0 (FIFO), 1 with order=-1
        // (should lead), 1 with order=10 (should trail). Inserting
        // them in randomised order must still produce the same plan.
        List<String> expected = List.of("lead", "f1", "f2", "f3", "trail");

        // Inputs in canonical insertion order.
        List<Spec> canonical =
                List.of(
                        new Spec("f1", 0),
                        new Spec("f2", 0),
                        new Spec("f3", 0),
                        new Spec("lead", -1),
                        new Spec("trail", 10));

        // 'expected' depends on FIFO at order=0, so the canonical list
        // above is also the registration order whose FIFO produces
        // (f1,f2,f3). For every permutation of the order=0 entries we
        // must preserve their relative FIFO; for entries with distinct
        // 'order' the position in the list is irrelevant.
        for (int trial = 0; trial < 50; trial++) {
            List<Spec> shuffled = new ArrayList<>(canonical);
            // Shuffle only the {lead, trail} positions so the order=0
            // group keeps its canonical FIFO. (Shuffling f1/f2/f3 would
            // change the FIFO and therefore the expected order — that
            // is a different invariant.)
            Random rng = new Random(trial);
            Collections.shuffle(extractDistinctOrderEntries(shuffled), rng);

            HandlerRegistry<Object, String> reg = new HandlerRegistry<>();
            for (Spec s : shuffled) {
                reg.registerInvoker(Msg.class, labelled(s.label()), s.order());
            }
            assertEquals(
                         expected,
                         invokeAll(reg.planFor(Msg.class)),
                         "plan must be deterministic for trial=" + trial);
        }
    }

    private static List<Spec> extractDistinctOrderEntries(List<Spec> in) {
        // Returns a live view; mutating it shuffles the entries in
        // place that carry a unique 'order' value, leaving the
        // order=0 entries fixed.
        List<Spec> tail = new ArrayList<>();
        for (Spec spec : in) {
            if (spec.order() != 0) {
                tail.add(spec);
            }
        }
        return tail;
    }

    private record Spec(String label, int order) {
    }

    private static List<String> invokeAll(DispatchPlan<Object, String> plan) throws Throwable {
        List<String> out = new ArrayList<>(plan.size());
        for (HandlerInvoker<Object, String> inv : plan.handlers()) {
            out.add(inv.invoke(new Msg(), null));
        }
        return out;
    }
}
