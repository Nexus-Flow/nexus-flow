package net.nexus_flow.core.eventsourcing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link ProjectionRunnerConfig} contract: defaults, builder propagation, validation
 * rejection.
 */
class ProjectionRunnerConfigTest {

    @Test
    void defaults_carryFrameworkDefaults() {
        ProjectionRunnerConfig cfg = ProjectionRunnerConfig.DEFAULTS;
        assertEquals(ProjectionRunnerConfig.DEFAULT_BATCH_SIZE, cfg.batchSize());
        assertEquals(ProjectionRunnerConfig.DEFAULT_POLL_INTERVAL, cfg.pollInterval());
        assertEquals(ProjectionRunnerConfig.DEFAULT_SHUTDOWN_GRACE, cfg.shutdownGrace());
    }

    @Test
    void builder_overridesPropagate() {
        ProjectionRunnerConfig cfg =
                ProjectionRunnerConfig.builder()
                        .batchSize(128L)
                        .pollInterval(Duration.ofMillis(10))
                        .shutdownGrace(Duration.ofSeconds(10))
                        .build();
        assertEquals(128L, cfg.batchSize());
        assertEquals(Duration.ofMillis(10), cfg.pollInterval());
        assertEquals(Duration.ofSeconds(10), cfg.shutdownGrace());
    }

    @Test
    void rejects_batchSizeBelow1() {
        assertThrows(
                     IllegalArgumentException.class,
                     () -> ProjectionRunnerConfig.builder().batchSize(0L).build());
    }

    @Test
    void rejects_nonPositivePollInterval() {
        assertThrows(
                     IllegalArgumentException.class,
                     () -> ProjectionRunnerConfig.builder().pollInterval(Duration.ZERO).build());
        assertThrows(
                     IllegalArgumentException.class,
                     () -> ProjectionRunnerConfig.builder().pollInterval(Duration.ofMillis(-1)).build());
    }

    @Test
    void rejects_nonPositiveShutdownGrace() {
        assertThrows(
                     IllegalArgumentException.class,
                     () -> ProjectionRunnerConfig.builder().shutdownGrace(Duration.ZERO).build());
    }

    @Test
    void rejects_nullDurations() {
        assertThrows(
                     NullPointerException.class,
                     () -> ProjectionRunnerConfig.builder().pollInterval(null).build());
        assertThrows(
                     NullPointerException.class,
                     () -> ProjectionRunnerConfig.builder().shutdownGrace(null).build());
    }
}
