package net.nexus_flow.core.cqrs.event;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.DomainEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hardening tests for {@link ScopedDomainEventContext} that the v0.1.0 audit flagged as needing
 * stronger guarantees:
 *
 * <ul>
 * <li>The internal bookkeeping must not retain references to bound sinks after the lexical scope
 * unwinds (GC reachability test).
 * <li>Nested scopes must remain isolated — an inner scope's events must not leak into the outer
 * scope's sink.
 * <li>The fallback (non-scoped) thread-local path must remain confined per thread under
 * concurrent recording.
 * </ul>
 */
class ScopedDomainEventContextNoLeakAndNestingTest {

    static final class Tick extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;
        private final String      label;

        Tick(String label) {
            super("scope-test");
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    @Test
    @DisplayName("Bound sink becomes weakly reachable after the scope exits — no leak")
    @SuppressWarnings("PMD.DoNotCallGarbageCollectionExplicitly")
    void boundSink_isGarbageCollectableAfterScopeExit() {
        ScopedDomainEventContext                     context = new ScopedDomainEventContext();
        WeakReference<ScopedDomainEventContext.Sink> weakRef = bindAndDropSink(context);

        await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(
                               () -> {
                                   System.gc();
                                   assertNull(weakRef.get(), "no leak: nothing must hold the sink after scope exit");
                               });
    }

    private static WeakReference<ScopedDomainEventContext.Sink> bindAndDropSink(
            ScopedDomainEventContext context) {
        ScopedDomainEventContext.Sink                sink = new ScopedDomainEventContext.Sink();
        WeakReference<ScopedDomainEventContext.Sink> ref  = new WeakReference<>(sink);
        ScopedValue.where(context.getScopedValue(), sink)
                .run(
                     () -> {
                         context.recordEvent(new Tick("alpha"));
                         assertTrue(context.hasEventsRecorded());
                     });
        // strong reference goes out of scope on return
        return ref;
    }

    @Test
    @DisplayName("Nested scopes do not bleed events between inner and outer sinks")
    void nestedScopes_remainIsolated() {
        ScopedDomainEventContext      context   = new ScopedDomainEventContext();
        ScopedDomainEventContext.Sink outerSink = new ScopedDomainEventContext.Sink();
        ScopedDomainEventContext.Sink innerSink = new ScopedDomainEventContext.Sink();

        ScopedValue.where(context.getScopedValue(), outerSink)
                .run(
                     () -> {
                         context.recordEvent(new Tick("outer-before"));
                         assertEquals(1, outerSink.events().size());

                         ScopedValue.where(context.getScopedValue(), innerSink)
                                 .run(
                                      () -> {
                                          context.recordEvent(new Tick("inner"));
                                          assertEquals(1, innerSink.events().size());
                                          assertTrue(context.hasEventsRecorded());
                                      });

                         // After the inner scope exits, the outer sink must be intact and the
                         // inner sink must remain disjoint.
                         assertEquals(1, outerSink.events().size());
                         assertEquals(1, innerSink.events().size());
                         assertEquals("outer-before", ((Tick) outerSink.events().get(0)).label());
                         assertEquals("inner", ((Tick) innerSink.events().get(0)).label());

                         context.recordEvent(new Tick("outer-after"));
                         assertEquals(2, outerSink.events().size());
                         assertEquals(1, innerSink.events().size(), "outer record must not leak into inner");
                     });
    }

    @Test
    @DisplayName("Fallback path is per-thread: concurrent recorders never observe each other")
    void fallbackPath_isThreadConfinedUnderConcurrentLoad() throws Exception {
        ScopedDomainEventContext        context         = new ScopedDomainEventContext();
        int                             threads         = 32;
        int                             eventsPerThread = 100;
        CountDownLatch                  start           = new CountDownLatch(1);
        CountDownLatch                  done            = new CountDownLatch(threads);
        AtomicReference<AssertionError> failure         = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            final String tag = "t-" + t;
            Thread.ofVirtual()
                    .start(
                           () -> {
                               try {
                                   start.await();
                                   for (int i = 0; i < eventsPerThread; i++) {
                                       context.recordEvent(new Tick(tag));
                                   }
                                   List<DomainEvent> events = context.getEvents();
                                   if (events.size() != eventsPerThread) {
                                       failure.compareAndSet(
                                                             null,
                                                             new AssertionError(
                                                                     "thread "
                                                                             + tag
                                                                             + " observed "
                                                                             + events.size()
                                                                             + " events, expected "
                                                                             + eventsPerThread));
                                   }
                                   // Every event must carry our own tag — otherwise another thread
                                   // bled into ours.
                                   for (DomainEvent ev : events) {
                                       if (!tag.equals(((Tick) ev).label())) {
                                           failure.compareAndSet(
                                                                 null, new AssertionError("cross-thread leak detected from " + tag));
                                           break;
                                       }
                                   }
                                   context.clearEvents();
                               } catch (InterruptedException e) {
                                   Thread.currentThread().interrupt();
                               } finally {
                                   done.countDown();
                               }
                           });
        }

        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "all worker threads should finish in time");
        AssertionError failed = failure.get();
        if (failed != null) {
            throw failed;
        }
    }

    @Test
    @DisplayName("Sink.recorded() flag survives buffer drain by the runtime")
    void recordedFlag_stickyAcrossDrain() {
        ScopedDomainEventContext      context = new ScopedDomainEventContext();
        ScopedDomainEventContext.Sink sink    = new ScopedDomainEventContext.Sink();

        ScopedValue.where(context.getScopedValue(), sink)
                .run(
                     () -> {
                         context.recordEvent(new Tick("once"));
                         // Simulate the runtime draining the buffer into a downstream consumer
                         // without calling clearEvents/resetEventsRecorded.
                         List<DomainEvent> drained = new ArrayList<>(sink.events());
                         sink.events().clear();
                         assertEquals(1, drained.size());
                         assertTrue(
                                    context.hasEventsRecorded(),
                                    "recorded flag must remain true even after the runtime drained the buffer");
                         context.resetEventsRecorded();
                         assertFalse(context.hasEventsRecorded());
                     });
    }

    @Test
    @DisplayName("Unique sink identity per scope guarantees no cross-talk through static state")
    void uniqueSinkIdentity_perScope() {
        ScopedDomainEventContext      context = new ScopedDomainEventContext();
        ScopedDomainEventContext.Sink first   = new ScopedDomainEventContext.Sink();
        ScopedDomainEventContext.Sink second  = new ScopedDomainEventContext.Sink();

        String correlation1 = UUID.randomUUID().toString();
        String correlation2 = UUID.randomUUID().toString();

        ScopedValue.where(context.getScopedValue(), first)
                .run(() -> context.recordEvent(new Tick(correlation1)));

        ScopedValue.where(context.getScopedValue(), second)
                .run(() -> context.recordEvent(new Tick(correlation2)));

        assertEquals(1, first.events().size());
        assertEquals(1, second.events().size());
        assertEquals(correlation1, ((Tick) first.events().get(0)).label());
        assertEquals(correlation2, ((Tick) second.events().get(0)).label());
        assertNotSame(first, second);
    }
}
