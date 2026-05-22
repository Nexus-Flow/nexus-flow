package net.nexus_flow.core.saga;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import net.nexus_flow.core.eventsourcing.EventStore;
import net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec;
import net.nexus_flow.core.outbox.OutboxPayloadCodec;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link SagaRunnerConfig} contract: every field has a sensible default, builder overrides
 * propagate verbatim, validation rejects illegal inputs.
 */
class SagaRunnerConfigTest {

    @Test
    void defaults_carryFrameworkDefaults() {
        SagaRunnerConfig cfg = SagaRunnerConfig.DEFAULTS;
        assertEquals(SagaRunnerConfig.DEFAULT_BATCH_SIZE, cfg.batchSize());
        assertEquals(EventStore.FIRST_GLOBAL_POSITION, cfg.startGlobalPosition());
        assertNotNull(cfg.clock());
        assertNull(cfg.codec(), "default codec is null");
    }

    @Test
    void builder_overridesFields_propagateVerbatim() {
        Clock              fixed = Clock.systemUTC();
        OutboxPayloadCodec codec = new JavaSerializationOutboxPayloadCodec();
        SagaRunnerConfig   cfg   =
                SagaRunnerConfig.builder()
                        .batchSize(64L)
                        .startGlobalPosition(500L)
                        .clock(fixed)
                        .codec(codec)
                        .build();
        assertEquals(64L, cfg.batchSize());
        assertEquals(500L, cfg.startGlobalPosition());
        assertEquals(fixed, cfg.clock());
        assertEquals(codec, cfg.codec());
    }

    @Test
    void compactConstructor_rejectsBatchSizeBelow1() {
        assertThrows(
                     IllegalArgumentException.class, () -> SagaRunnerConfig.builder().batchSize(0L).build());
        assertThrows(
                     IllegalArgumentException.class, () -> SagaRunnerConfig.builder().batchSize(-1L).build());
    }

    @Test
    void compactConstructor_rejectsStartBelowFirstGlobalPosition() {
        assertThrows(
                     IllegalArgumentException.class,
                     () -> SagaRunnerConfig.builder().startGlobalPosition(0L).build());
        assertThrows(
                     IllegalArgumentException.class,
                     () -> SagaRunnerConfig.builder().startGlobalPosition(-5L).build());
    }

    @Test
    void compactConstructor_rejectsNullClock() {
        assertThrows(NullPointerException.class, () -> SagaRunnerConfig.builder().clock(null).build());
    }

    @Test
    void codec_acceptsNull() {
        SagaRunnerConfig cfg = SagaRunnerConfig.builder().codec(null).build();
        assertNull(cfg.codec());
    }
}
