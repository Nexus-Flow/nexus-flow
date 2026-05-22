package net.nexus_flow.core.runtime.ids;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Framework-internal helper that generates v4 UUIDs from {@link ThreadLocalRandom} instead of
 * {@link UUID#randomUUID()}.
 *
 * <p><strong>When to call this:</strong> framework-internal id types used purely as
 * <em>observability handles</em> or <em>storage surrogates</em> — {@link MessageId}, {@link
 * TraceId}, {@link CorrelationId}, {@code InboxId}, {@code SagaId}, {@code ScheduledCommandId}, the
 * {@code AbstractDomainEvent#id} field, the {@code DefaultCommand}/{@code DefaultQuery} envelope-id
 * fallbacks. These handles are stamped on the dispatch envelope, appear in logs, propagate to
 * outbox rows, and surface in distributed-tracing exports. They are never used as session tokens,
 * password resets, CSRF nonces, or any other crypto-sensitive channel. The 122-bit collision space
 * of UUID v4 is overkill for de-duplication and dwarfs any realistic dispatch volume; the source of
 * those 122 bits does not need to be cryptographically strong.
 *
 * <p><strong>When NOT to call this — privacy-boundary identifiers.</strong> If the id is exposed as
 * part of an external URL or API response that an attacker can enumerate to discover other users'
 * resources (the classic example is an aggregate id surfacing as {@code /orders/{id}}), the
 * predictability of {@link ThreadLocalRandom} is an enumeration risk and the caller MUST stay on
 * {@code UUID.randomUUID()} (SecureRandom-backed). {@code AggregateRoot} keeps that wider default
 * on purpose. When in doubt, default to {@code UUID.randomUUID()} — the performance win only
 * matters at hot-path call rates.
 *
 * <p>{@code UUID.randomUUID()} draws from the JDK's {@code SecureRandom} — even on the modern
 * NativePRNG-backed implementations it carries a per-call overhead an order of magnitude above the
 * fast non-secure path. On the dispatch hot path the framework allocates one {@code MessageId} per
 * listener per event — at 1 M events/s × 10 listeners that is 10 M UUIDs/s, a real allocation / CPU
 * budget regression compared to the &lt;100 ns ThreadLocalRandom path.
 *
 * <p><strong>Wire-format equivalence.</strong> The bit-mangling here mirrors {@code
 * UUID.randomUUID()} exactly: clear the version nibble on the most-significant {@code long} and set
 * it to {@code 0x4} (RFC 4122 v4); clear the IETF variant bits on the least-significant {@code
 * long} and set them to {@code 0b10}. Output is wire-format-identical to {@code UUID.randomUUID()}
 * — operators inspecting logs cannot distinguish the two.
 *
 * <p><strong>Visibility.</strong> This class is {@code public} only because it is consumed from
 * multiple framework-internal packages ({@code runtime.ids}, {@code inbox}, {@code saga}, {@code
 * scheduling}, {@code ddd}, {@code cqrs.command}, {@code cqrs.query}). It is NOT an adapter-facing
 * SPI — external integrators have no reason to call it and should use {@link UUID#randomUUID()}
 * directly. The promise of bit-format equivalence with {@code UUID.randomUUID()} is the only
 * stability guarantee.
 */
public final class FastUuid {

    private FastUuid() {
    }

    /**
     * Returns a fresh v4 {@link UUID} drawn from {@link ThreadLocalRandom}. Wire-format-identical to
     * {@link UUID#randomUUID()} but ~10× faster on modern JDKs.
     *
     * @return a v4 UUID
     */
    public static UUID v4() {
        ThreadLocalRandom tlr = ThreadLocalRandom.current();
        long              msb = tlr.nextLong();
        long              lsb = tlr.nextLong();
        // Clear version nibble, set to 0x4 (RFC 4122 v4)
        msb &= 0xFFFFFFFFFFFF0FFFL;
        msb |= 0x0000000000004000L;
        // Clear variant bits, set IETF variant (10xx_xxxx)
        lsb &= 0x3FFFFFFFFFFFFFFFL;
        lsb |= 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
