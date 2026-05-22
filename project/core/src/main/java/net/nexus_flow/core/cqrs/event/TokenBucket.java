package net.nexus_flow.core.cqrs.event;

/**
 * SPI — minimal token-bucket contract used by {@link ListenerExecutor} to enforce {@link
 * EventListener#rateLimit() per-listener rate limiting}.
 *
 * <p>The {@code core} module ships the in-process default ({@code InMemoryTokenBucket}).
 * Framework-integration modules can plug in distributed implementations — e.g. Bucket4j with a
 * Lettuce/Jedis backend on top of Redis — by returning a custom {@link TokenBucket} from {@link
 * EventListener#tokenBucket()}.
 *
 * <p><strong>Thread-safety.</strong> A bucket instance is shared by every dispatch path that
 * targets the owning listener (which, in fan-out mode, is potentially many threads) and MUST
 * therefore be implemented thread-safe.
 *
 * <p><strong>Contract.</strong> {@link #tryAcquire()} consumes one token and returns {@code true}
 * on success or {@code false} when the bucket is empty. It MUST NOT block; the caller (the listener
 * executor) treats {@code false} as a non-recoverable rate-limit decision for the current event and
 * records it under {@link ListenerStats#rateLimited()}.
 */
@FunctionalInterface
public interface TokenBucket {

    /**
     * Attempts to consume one token from the bucket.
     *
     * @return {@code true} if a token was available and consumed, {@code false} if the bucket is
     *         currently empty.
     */
    boolean tryAcquire();
}
