package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Pins the contract of {@link OutboxConfig#workerBackoffBase()} / {@link
 * OutboxConfig#workerBackoffMax()} and the parameterised {@link OutboxWorker#computeBackoff(int,
 * Duration, Duration)} overload.
 *
 * <p>The §11 audit verification surfaced that {@code BACKOFF_BASE} / {@code BACKOFF_MAX} were
 * static-final constants on {@link OutboxWorker} with no operator override — the user's notes
 * incorrectly assumed those values were already exposed via {@code OutboxConfig.Builder}. The fix
 * surfaces both as record components on {@link OutboxConfig} with builder setters, and the {@code
 * computeBackoff} algorithm now reads them from config at retry-decision time.
 */
class OutboxWorkerBackoffConfigurableTest {

    @Test
    void computeBackoff_withTinyBase_producesTinyDelays() {
        Duration base = Duration.ofMillis(1);
        Duration max  = Duration.ofMillis(8);
        long     min  = Long.MAX_VALUE, maxObserved = Long.MIN_VALUE;
        for (int i = 0; i < 200; i++) {
            long ms = OutboxWorker.computeBackoff(1, base, max).toMillis();
            min         = Math.min(min, ms);
            maxObserved = Math.max(maxObserved, ms);
        }
        // Attempt 1, base 1ms: nominal 1ms × [0.8, 1.2] then Math.max(1, …). Observed ms ∈ [1, 2].
        assertTrue(min >= 1L, "min must be >= 1ms (Math.max guard); got " + min);
        assertTrue(maxObserved <= 2L, "max must be <= 2ms; got " + maxObserved);
    }

    @Test
    void computeBackoff_clampsAtConfiguredMax() {
        Duration base = Duration.ofMillis(10);
        Duration max  = Duration.ofMillis(50);
        // Very high attempt count must converge on the cap (modulo jitter window).
        long maxObserved = Long.MIN_VALUE;
        for (int i = 0; i < 200; i++) {
            long ms = OutboxWorker.computeBackoff(1_000, base, max).toMillis();
            maxObserved = Math.max(maxObserved, ms);
        }
        // Cap is 50ms, jitter top is 1.2 → upper observed bound ≈ 60ms.
        assertTrue(maxObserved <= 60L, "max must be capped + jitter; got " + maxObserved);
    }

    @Test
    void defaultOverload_usesConfigDefaults() {
        // Sanity: the no-arg overload still delegates to the framework defaults.
        long observed         = OutboxWorker.computeBackoff(1).toMillis();
        long expectedMinBound = (long) (OutboxConfig.DEFAULT_BACKOFF_BASE.toMillis() * 0.8d) - 1L;
        long expectedMaxBound = (long) (OutboxConfig.DEFAULT_BACKOFF_BASE.toMillis() * 1.2d) + 1L;
        assertTrue(
                   observed >= expectedMinBound && observed <= expectedMaxBound,
                   "default overload must respect framework defaults; got "
                           + observed
                           + " expected ∈ ["
                           + expectedMinBound
                           + ", "
                           + expectedMaxBound
                           + "]");
    }

    @Test
    void builder_rejectsNegativeOrZeroBackoffBase() {
        OutboxStorage      storage = new InMemoryOutboxStorage();
        OutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
        assertThrows(
                     IllegalArgumentException.class,
                     () -> OutboxConfig.builder(storage, codec).workerBackoffBase(Duration.ZERO).build());
        assertThrows(
                     IllegalArgumentException.class,
                     () -> OutboxConfig.builder(storage, codec).workerBackoffBase(Duration.ofMillis(-1)).build());
    }

    @Test
    void builder_rejectsMaxLowerThanBase() {
        OutboxStorage      storage = new InMemoryOutboxStorage();
        OutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
        assertThrows(
                     IllegalArgumentException.class,
                     () -> OutboxConfig.builder(storage, codec)
                             .workerBackoffBase(Duration.ofSeconds(2))
                             .workerBackoffMax(Duration.ofSeconds(1))
                             .build());
    }

    @Test
    void builder_propagatesValuesIntoRecord() {
        OutboxStorage      storage = new InMemoryOutboxStorage();
        OutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
        Duration           base    = Duration.ofMillis(250);
        Duration           max     = Duration.ofSeconds(60);
        Duration           grace   = Duration.ofMillis(750);

        OutboxConfig cfg =
                OutboxConfig.builder(storage, codec)
                        .workerBackoffBase(base)
                        .workerBackoffMax(max)
                        .workerShutdownGrace(grace)
                        .build();

        assertEquals(base, cfg.workerBackoffBase());
        assertEquals(max, cfg.workerBackoffMax());
        assertEquals(grace, cfg.workerShutdownGrace());
    }
}
