package net.nexus_flow.core.ring.membership;

/**
 * Pluggable strategy that drives a {@link MembershipRegistry}. The strategy is responsible for
 * discovering peers, dialing them, monitoring health, and committing transitions through the
 * registry's mutation API ({@code register}, {@code transition}, {@code recordPong}).
 *
 * <p>Higher layers ({@link MembershipRegistry} consumers — R5/R6/R7/R8) interact with the
 * registry, not the strategy. The strategy is the implementation detail that produces the
 * membership view; consumers care only about the view itself.
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 * <li>{@link #start()} — connect to seed peers / open gossip channels / start the
 * failure-detector threads. Idempotent: second call is a no-op.
 * <li>{@link #registry()} — return the live registry consumers subscribe to.
 * <li>{@link #shutdown()} — stop background threads, close connections. Idempotent.
 * </ol>
 */
public interface MembershipStrategy extends AutoCloseable {

    /** Starts the strategy. Idempotent. */
    void start();

    /** Shuts down the strategy. Idempotent. */
    void shutdown();

    /** {@link AutoCloseable} alias for {@link #shutdown()}. */
    @Override
    default void close() {
        shutdown();
    }

    /** The registry this strategy maintains. Stable for the lifetime of the strategy. */
    MembershipRegistry registry();
}
