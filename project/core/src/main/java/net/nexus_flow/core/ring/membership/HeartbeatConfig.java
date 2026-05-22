package net.nexus_flow.core.ring.membership;

import java.time.Duration;
import java.util.Objects;

/**
 * Tuning for {@link HeartbeatFailureDetector}.
 *
 * <h2>Time budget</h2>
 *
 * The detector classifies a peer as {@link PeerState#SUSPECT} after {@code missesUntilSuspect}
 * probe intervals with no PONG, and as {@link PeerState#CONFIRMED_DEAD} after an additional
 * {@code suspectGrace}. Total time to detect dead = {@code probeInterval *
 * missesUntilSuspect + suspectGrace}. Defaults give a ~3.5 s detection window — fast enough
 * for sub-second p99 routing reactions, slow enough to ride out brief GC pauses.
 *
 * @param probeInterval      time between probes per peer; default {@value #DEFAULT_PROBE_INTERVAL_MS} ms
 * @param missesUntilSuspect consecutive missed PONGs before flipping to SUSPECT; default 3
 * @param suspectGrace       time in SUSPECT before flipping to CONFIRMED_DEAD; default {@value
 *                           #DEFAULT_SUSPECT_GRACE_MS} ms
 * @param shutdownGrace      bound on {@link HeartbeatFailureDetector#shutdown()} join; default
 *                           {@value #DEFAULT_SHUTDOWN_GRACE_MS} ms
 * @param localPeerId        this pod's peer id; embedded in every outgoing PING so receivers know
 *                           who is probing
 */
public record HeartbeatConfig(
                              Duration probeInterval,
                              int missesUntilSuspect,
                              Duration suspectGrace,
                              Duration shutdownGrace,
                              net.nexus_flow.core.ring.transport.PeerId localPeerId) {

    public static final long DEFAULT_PROBE_INTERVAL_MS = 500L;
    public static final int DEFAULT_MISSES_UNTIL_SUSPECT = 3;
    public static final long DEFAULT_SUSPECT_GRACE_MS = 2_000L;
    public static final long DEFAULT_SHUTDOWN_GRACE_MS = 2_000L;

    public HeartbeatConfig {
        Objects.requireNonNull(probeInterval, "probeInterval");
        if (probeInterval.isNegative() || probeInterval.isZero()) {
            throw new IllegalArgumentException("probeInterval must be positive: " + probeInterval);
        }
        if (missesUntilSuspect < 1) {
            throw new IllegalArgumentException(
                    "missesUntilSuspect must be >= 1: " + missesUntilSuspect);
        }
        Objects.requireNonNull(suspectGrace, "suspectGrace");
        if (suspectGrace.isNegative()) {
            throw new IllegalArgumentException("suspectGrace must be >= 0: " + suspectGrace);
        }
        Objects.requireNonNull(shutdownGrace, "shutdownGrace");
        if (shutdownGrace.isNegative() || shutdownGrace.isZero()) {
            throw new IllegalArgumentException("shutdownGrace must be positive: " + shutdownGrace);
        }
        Objects.requireNonNull(localPeerId, "localPeerId");
    }

    /** Production-default factory. */
    public static HeartbeatConfig defaults(net.nexus_flow.core.ring.transport.PeerId localPeerId) {
        return new HeartbeatConfig(
                Duration.ofMillis(DEFAULT_PROBE_INTERVAL_MS),
                DEFAULT_MISSES_UNTIL_SUSPECT,
                Duration.ofMillis(DEFAULT_SUSPECT_GRACE_MS),
                Duration.ofMillis(DEFAULT_SHUTDOWN_GRACE_MS),
                localPeerId);
    }
}
