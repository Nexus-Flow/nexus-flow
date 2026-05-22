package net.nexus_flow.core.outbox;

/**
 * Strategy that decides how the runtime delivers domain events emitted during a command handler
 * (or during the worker-side dispatch of an outbox row) when an outbox is bound.
 *
 * <p>The sealed hierarchy is intentionally explicit so adapter modules can pattern-match
 * exhaustively without a {@code default} branch, and so the addition of a new variant
 * (e.g. a Kafka-producer-backed broadcaster) surfaces as a compile-time obligation across
 * every consumer in the codebase.
 *
 * <h2>Current variants</h2>
 *
 * <ul>
 * <li>{@link OutboxOnly} — the outbox worker is the SOLE publisher. The inline event bus is
 * NOT fired for events that are written to the outbox during a handler drain. Provides
 * single-delivery semantics out of the box without an InboxStorage layer; relies on the
 * framework's worker-side recursive drain (handled in {@code OutboxWorker.processOneInner})
 * so listener-emitted cascade events still propagate. <strong>This is the safe default
 * when an outbox is bound.</strong>
 * <li>{@link InlinePlusOutbox} — the inline event bus AND the outbox worker both publish.
 * Lower latency for local listeners at the cost of double delivery unless an
 * {@code InboxStorage} dedup layer is wired. The framework's {@code OutboxConfig}
 * construction emits a WARNING when this strategy is selected without an inbox so the
 * trade-off is loud and obvious.
 * </ul>
 *
 * <h2>Future-reserved seats</h2>
 *
 * Adapter-module extension points whose seats are reserved in the sealed contract but whose
 * concrete implementations live outside {@code core}:
 *
 * <ul>
 * <li>{@code KafkaProducerBroadcast} — produce events to a Kafka topic in addition to (or
 * instead of) the outbox.
 * <li>{@code RingBroadcast} — fan out events across the cluster ring transport.
 * <li>{@code WebhookFanOut} — POST events to a registered webhook endpoint.
 * </ul>
 *
 * <p>Adding such variants requires extending the {@code permits} clause; downstream
 * exhaustive switches will then refuse to compile until they handle the new case, surfacing
 * the operational surprise at build time rather than at runtime.
 *
 * <h2>Choosing a strategy</h2>
 *
 * <p>The framework picks {@link OutboxOnly} by default when an outbox is bound. Callers that
 * understand the trade-offs and need lower local-listener latency can opt into
 * {@link InlinePlusOutbox} and accept the warning (or wire an {@code InboxStorage} to silence
 * it). Callers that want to invert the relationship — for example, a "fast inline path with
 * outbox-as-backup" pattern — should compose with the appropriate {@code InboxStorage}; the
 * strategy itself is not a placeholder for the application-side delivery contract.
 */
public sealed interface EventDeliveryStrategy
        permits EventDeliveryStrategy.OutboxOnly, EventDeliveryStrategy.InlinePlusOutbox {

    /** Singleton instance of {@link OutboxOnly}. Stateless and cheap to share process-wide. */
    OutboxOnly OUTBOX_ONLY = new OutboxOnly();

    /**
     * Singleton instance of {@link InlinePlusOutbox}. Stateless; the {@code requireInboxDedup}
     * field is reserved for future tightening (e.g. fail-fast if the inbox is missing) but
     * currently treats both flag values uniformly — the warning is emitted from
     * {@code OutboxConfig} regardless.
     */
    InlinePlusOutbox INLINE_PLUS_OUTBOX = new InlinePlusOutbox();

    /**
     * Outbox worker is the SOLE publisher; the inline event bus skips fan-out for events
     * destined for the outbox. Cascade from listener-emitted aggregate events is preserved
     * via the framework's worker-side recursive drain. See class-level Javadoc for trade-offs.
     */
    record OutboxOnly() implements EventDeliveryStrategy {
    }

    /**
     * Inline event bus AND outbox worker both publish. The inline path delivers immediately
     * for low latency; the worker re-publishes from the outbox. Local listeners that have not
     * opted into idempotency observe the event TWICE unless an {@code InboxStorage} dedup
     * layer is wired. {@code OutboxConfig} emits a WARNING at construction time when this
     * strategy is selected without an inbox.
     */
    record InlinePlusOutbox() implements EventDeliveryStrategy {
    }

    /** @return the safe default strategy when an outbox is bound. */
    static EventDeliveryStrategy outboxOnly() {
        return OUTBOX_ONLY;
    }

    /**
     * @return the {@link InlinePlusOutbox} strategy. Callers must understand the double-delivery
     *         risk (wire an {@code InboxStorage} for dedup, or migrate listeners to idempotent
     *         processing).
     */
    static EventDeliveryStrategy inlinePlusOutbox() {
        return INLINE_PLUS_OUTBOX;
    }

    /**
     * Boolean shortcut: {@code true} selects {@link #outboxOnly()}, {@code false} selects
     * {@link #inlinePlusOutbox()}. Useful for the convenience builder shortcut and for the
     * {@link OutboxConfig#useOutboxFanOut()} accessor that downstream tooling still reads.
     *
     * @param killSwitchEnabled {@code true} to make the outbox worker the sole publisher
     * @return the matching strategy singleton
     */
    static EventDeliveryStrategy forKillSwitch(boolean killSwitchEnabled) {
        return killSwitchEnabled ? OUTBOX_ONLY : INLINE_PLUS_OUTBOX;
    }
}
