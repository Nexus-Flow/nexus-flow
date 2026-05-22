package net.nexus_flow.core.cqrs.command;

/**
 * Per-handler concurrency cap configuration.
 *
 * <p>One setting:
 *
 * <ul>
 * <li>{@code maxLevel} — upper bound applied to the value returned by {@link
 * CommandHandler#getConcurrencyLevel()} at executor construction time. Handlers that return a
 * value above this cap are clamped and a {@code WARNING} is logged. Must be {@code >= 1};
 * default {@code 1024}.
 * </ul>
 *
 * <p>Wired in through {@link CommandSettings#concurrency()} so individual handlers can raise or
 * lower the safety cap via {@link CommandSettings.Builder#concurrency(ConcurrencySettings)}. The
 * executor snapshots the cap at construction time; runtime mutation of the settings record after
 * the executor is built has no effect (records are immutable, executors are wired once).
 *
 * <p>The default cap of {@code 1024} is intentionally conservative. Workloads that legitimately
 * need more in-flight invocations per handler should raise the cap explicitly rather than relying
 * on the default; workloads requiring truly unbounded parallelism should compose a custom bounded
 * executor with external rate limiting.
 */
public record ConcurrencySettings(int maxLevel) {

    /** Sensible default: 1024 maximum in-flight invocations per handler. */
    private static final ConcurrencySettings DEFAULTS = new ConcurrencySettings(1024);

    /**
     * Validates the settings.
     *
     * @throws IllegalArgumentException if {@code maxLevel} is less than {@code 1}
     */
    public ConcurrencySettings {
        if (maxLevel < 1) {
            throw new IllegalArgumentException("maxLevel must be >= 1 (got " + maxLevel + ")");
        }
    }

    /**
     * @return {@link #DEFAULTS}.
     */
    public static ConcurrencySettings defaults() {
        return DEFAULTS;
    }
}
