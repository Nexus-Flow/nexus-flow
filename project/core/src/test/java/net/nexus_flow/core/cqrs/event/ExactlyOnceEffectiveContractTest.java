package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.junit.jupiter.api.Test;

/**
 * exactly-once-effective probative contract.
 *
 * <p>This test does NOT install theOutbox; it pins the <em>precondition</em> the Outbox will rely
 * on: a listener that deduplicates by {@link DomainEvent#idempotencyKey()} processes the same event
 * only once, no matter how many times it is published.
 *
 * <p>Scenario: a flaky listener fails on its first invocation (simulating an "online" attempt that
 * crashed before commit); the runtime retries by republishing the same event (simulating an Outbox
 * replay). The listener observes both publications via the same idempotencyKey and short-circuits
 * the second one — proving the dedup handle is sufficient.
 */
class ExactlyOnceEffectiveContractTest {

    static final class Charged extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Charged(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Wallet extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        Charged emit() {
            Charged e = new Charged("wallet-7");
            recordEvent(e);
            return e;
        }
    }

    /** Deduplication by idempotency key ensures listeners process each event exactly once. */
    @Test
    void listenerDeduplicatingByIdempotencyKey_processesSameEventOnlyOnce() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            Set<String>   processedKeys = new HashSet<>();
            AtomicInteger sideEffect    = new AtomicInteger(0);
            AtomicInteger attempts      = new AtomicInteger(0);

            // The listener simulates a real consumer that records the
            // side-effect once per idempotencyKey AND throws on its
            // first invocation.
            var listener =
                    new AbstractDomainEventListener<Charged>() {
                        @Override
                        public void handle(Charged event) {
                            int attempt = attempts.incrementAndGet();
                            String key = event.idempotencyKey();
                            if (!processedKeys.add(key)) {
                                // Already processed in a prior publication.
                                // The Outbox replay must not double-charge.
                                return;
                            }
                            if (attempt == 1) {
                                // Simulate the "online" attempt crashing AFTER
                                // marking the key as in-flight — we explicitly
                                // roll the key back so the retry observes it as
                                // unprocessed and converges to exactly-once
                                // EFFECTIVE behaviour (one observable side-effect).
                                processedKeys.remove(key);
                                throw new RuntimeException("online attempt crashed");
                            }
                            sideEffect.incrementAndGet();
                        }
                    };
            runtime.events().register(listener);

            Wallet  wallet = new Wallet();
            Charged event  = wallet.emit();

            // Publication #1 — simulates the "online" attempt. Fails
            // with FailFast; the listener marked the key as in-flight
            // and then rolled it back when it crashed.
            DispatchResult<Void> first =
                    runtime.events().dispatchResult(event, ExecutionContext.root(), ErrorPolicy.failFast());
            assertInstanceOf(
                             DispatchResult.Failure.class,
                             first,
                             "online attempt was supposed to crash; got " + first);

            // Publication #2 — simulates an Outbox replay of the SAME
            // event (same instance ⇒ same idempotencyKey).
            DispatchResult<Void> second =
                    runtime.events().dispatchResult(event, ExecutionContext.root(), ErrorPolicy.failFast());
            assertInstanceOf(
                             DispatchResult.Success.class,
                             second,
                             "Outbox replay must converge to Success; got " + second);

            // Publication #3 — and a defensive third replay; even if
            // the Outbox shipped twice (network duplication), the
            // dedup must hold.
            DispatchResult<Void> third =
                    runtime.events().dispatchResult(event, ExecutionContext.root(), ErrorPolicy.failFast());
            assertInstanceOf(DispatchResult.Success.class, third);

            // The contract under test: exactly ONE observable
            // side-effect, regardless of how many times we published.
            assertEquals(
                         1,
                         sideEffect.get(),
                         " listener keyed on idempotencyKey must "
                                 + "process the event exactly once-effective. attempts="
                                 + attempts.get()
                                 + " sideEffect="
                                 + sideEffect.get());
            assertEquals(
                         3,
                         attempts.get(),
                         "listener WAS invoked three times (online crash + 2 replays); "
                                 + "dedup happens inside the listener");
            assertEquals(
                         Set.of("wallet-7:0"), processedKeys, "processed-keys set must contain the canonical key");
            assertTrue(
                       processedKeys.contains(event.idempotencyKey()),
                       "idempotencyKey derivation must match the one the listener observed");
        }
    }
}
