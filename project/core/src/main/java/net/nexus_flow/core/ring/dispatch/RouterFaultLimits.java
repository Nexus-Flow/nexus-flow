package net.nexus_flow.core.ring.dispatch;

import java.time.Duration;
import java.util.Objects;

/**
 * Per-peer fault-rate thresholds used by {@link RingFrameRouter} to decide when a misbehaving
 * connection must be evicted. A peer is permitted up to {@link #maxFaultsPerWindow()} subsystem
 * handler exceptions inside any rolling {@link #window()}; the {@code maxFaultsPerWindow + 1}<sup>th</sup>
 * fault triggers an unconditional {@code connection.close()}.
 *
 * <h2>Why a sliding window, not a single counter</h2>
 *
 * A monotonically-increasing counter would conflate a sustained noisy peer (genuinely
 * misbehaving) with a peer that occasionally hits a transient codec mismatch (a benign
 * deployment cross-version). The window scopes the count to recency, so a peer that has been
 * quiet for {@code window} drops back to a fresh budget without operator intervention.
 *
 * <h2>Defaults</h2>
 *
 * {@link #DEFAULTS} — {@code 10 faults / 60 s} — matches the value documented in the
 * {@link RingFrameRouter} class Javadoc since the router was introduced. Operators tighten the
 * threshold in lossy or untrusted environments; loosen it during deployments that produce
 * benign codec mismatches.
 *
 * @param maxFaultsPerWindow strictly positive integer — number of faults a peer may incur inside
 *                           one rolling window before being closed
 * @param window             strictly positive duration — width of the rolling window
 */
public record RouterFaultLimits(int maxFaultsPerWindow, Duration window) {

    /** Production-default fault budget: ten faults in a rolling minute. */
    public static final RouterFaultLimits DEFAULTS =
            new RouterFaultLimits(10, Duration.ofSeconds(60));

    /** Compact constructor — validates both knobs reject pathological values at construction. */
    public RouterFaultLimits {
        if (maxFaultsPerWindow < 1) {
            throw new IllegalArgumentException(
                    "maxFaultsPerWindow must be >= 1: " + maxFaultsPerWindow);
        }
        Objects.requireNonNull(window, "window");
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive: " + window);
        }
    }
}
