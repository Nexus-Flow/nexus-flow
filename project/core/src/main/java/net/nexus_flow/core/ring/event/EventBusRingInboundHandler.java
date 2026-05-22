package net.nexus_flow.core.ring.event;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.inbox.InboxClaim;
import net.nexus_flow.core.inbox.InboxStorage;
import net.nexus_flow.core.outbox.OutboxPayloadCodec;
import net.nexus_flow.core.ring.dispatch.RingFrameRouter;
import net.nexus_flow.core.ring.transport.RingConnection;
import net.nexus_flow.core.ring.wire.RingFrame;
import net.nexus_flow.core.ring.wire.RingProtocolException;
import net.nexus_flow.core.runtime.CancellationToken;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowScope;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TenantId;
import net.nexus_flow.core.runtime.ids.TraceId;
import org.jspecify.annotations.Nullable;

/**
 * Inbound counterpart of {@link RingEventBusBridge} / {@link RingOutboxBridge}. Receives every
 * EVENT frame from a peer, decodes the payload through the configured {@link OutboxPayloadCodec},
 * optionally claims a slot in the configured {@link InboxStorage} for exactly-once-effective
 * delivery, and re-publishes the event through the local {@link EventBus} under a child
 * {@link ExecutionContext} carrying the sender's trace / correlation / causation ids.
 *
 * <h2>The gap this closes</h2>
 *
 * Before this handler existed, {@link RingFrameRouter} accepted an optional inbound EVENT
 * handler that defaulted to {@code null}, so the runtime's wiring dropped every
 * cross-pod event silently. The live and durable bridges produced wire traffic that no peer
 * ever consumed — a unidirectional fan-out. The handler closes that gap: as long as the codec
 * id on the wire matches a registered local codec, the event reaches the local bus and every
 * registered listener fires as if the event had originated locally.
 *
 * <h2>Inbox deduplication (recommended for production)</h2>
 *
 * When an {@link InboxStorage} is wired, the handler claims by {@code (messageId, consumerId)}
 * before dispatching. {@code messageId} is a deterministic v3 UUID derived from the decoded
 * event's {@link DomainEvent#idempotencyKey()} — so the SAME event arriving via the live
 * (best-effort) path and the durable (at-least-once) path is dispatched exactly once. The
 * consumer id is {@link #DEFAULT_CONSUMER_ID} unless overridden; deployments that need separate
 * inbox columns per receiver subsystem set a custom id.
 *
 * <h2>Wire failure modes</h2>
 *
 * <ul>
 * <li>Envelope decode failure ({@link RingProtocolException}) — logged at {@code WARNING}; the
 * router classifies this as a protocol violation and counts it toward the per-peer fault
 * budget.
 * <li>Codec id mismatch — logged at {@code WARNING} and dropped. The receiver does not know
 * how to read the bytes; rejecting silently is safer than crashing the bus.
 * <li>Codec decode failure — logged at {@code WARNING} and dropped. Future enhancements may
 * route to a deserialisation dead-letter; for now the bridge keeps the row in the
 * sender's outbox (release-to-ready) so a fix-and-retry is possible.
 * <li>Listener exception — propagated through {@link EventBus#dispatchResult}; the bus's
 * dead-letter policy applies. The inbox row stays in {@code PROCESSING} until {@link
 * InboxStorage#markFailed} is called.
 * </ul>
 *
 * <h2>Thread-safety</h2>
 *
 * The handler runs on the connection's reader VT — one inbound EVENT processed at a time per
 * connection. Multiple connections (peers) can publish in parallel. The handler maintains no
 * mutable per-call state; the codec / inbox / bus must each tolerate concurrent calls.
 */
public final class EventBusRingInboundHandler implements RingFrameRouter.RingEventInboundHandler {

    private static final System.Logger LOG =
            System.getLogger(EventBusRingInboundHandler.class.getName());

    /** Default consumer id used when none is supplied via the builder. */
    public static final String DEFAULT_CONSUMER_ID = "nexus-ring-inbound";

    private final EventBus               eventBus;
    private final OutboxPayloadCodec     codec;
    private final String                 expectedCodecId;
    private final @Nullable InboxStorage inbox;
    private final String                 consumerId;
    private final Clock                  clock;

    private EventBusRingInboundHandler(Builder b) {
        this.eventBus        = b.eventBus;
        this.codec           = b.codec;
        this.expectedCodecId = b.expectedCodecId;
        this.inbox           = b.inbox;
        this.consumerId      = b.consumerId;
        this.clock           = b.clock;
    }

    @Override
    public void onEvent(RingConnection sender, RingFrame frame) {
        RingEventEnvelope envelope;
        try {
            envelope = RingEventEnvelope.decode(frame.bodyBytes());
        } catch (RingProtocolException protocolViolation) {
            // Rethrow so the router's per-peer fault tracker counts it; the read loop closes
            // the connection if the peer exceeds the protocol-violation budget.
            throw protocolViolation;
        }
        if (!expectedCodecId.equals(envelope.codecId())) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "ring inbound: dropping EVENT from " + sender.remoteAddress()
                            + " — codecId=" + envelope.codecId() + " does not match expected "
                            + expectedCodecId);
            return;
        }
        Class<?> payloadClass;
        try {
            payloadClass = Class.forName(
                                         envelope.payloadType(), false,
                                         currentClassLoader());
        } catch (ClassNotFoundException cnf) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "ring inbound: dropping EVENT — payload class not on classpath: "
                            + envelope.payloadType());
            return;
        }
        DomainEvent event;
        try {
            event = codec.decode(envelope.payloadBytes(), payloadClass);
        } catch (RuntimeException decodeFail) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "ring inbound: codec decode failed for " + envelope.payloadType()
                            + " from " + sender.remoteAddress() + ": " + decodeFail.getMessage());
            return;
        }
        dispatchWithDedup(envelope, event);
    }

    private void dispatchWithDedup(RingEventEnvelope envelope, DomainEvent event) {
        ExecutionContext childCtx = buildChildContext(envelope);
        InboxStorage     storage  = inbox;
        if (storage == null) {
            // No inbox configured — the application accepts at-least-once-effective semantics
            // for cross-pod events. Common for cache-invalidation / presence-update traffic.
            FlowScope.runWithContext(childCtx, () -> eventBus.dispatch(event, false));
            return;
        }
        MessageId  msgId = deriveMessageId(event);
        InboxClaim claim = storage.claimIfNew(msgId, consumerId, clock.instant());
        if (claim instanceof InboxClaim.Duplicate dup) {
            LOG.log(System.Logger.Level.DEBUG,
                    () -> "ring inbound: skipping duplicate event "
                            + event.idempotencyKey() + " (status=" + dup.status() + ")");
            return;
        }
        InboxClaim.Fresh fresh = (InboxClaim.Fresh) claim;
        boolean          ok    = false;
        try {
            FlowScope.runWithContext(childCtx, () -> eventBus.dispatch(event, false));
            ok = true;
        } finally {
            if (ok) {
                storage.markProcessed(fresh.id(), clock.instant());
            } else {
                storage.markFailed(fresh.id(),
                                   "listener exception during ring inbound dispatch",
                                   clock.instant());
            }
        }
    }

    private static MessageId deriveMessageId(DomainEvent event) {
        // v3 UUID derived from the event's domain-level idempotency key — same key from either
        // the live or durable path produces the same UUID, so the inbox dedupes across both.
        String key = event.idempotencyKey();
        return new MessageId(
                UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)));
    }

    private static ExecutionContext buildChildContext(RingEventEnvelope envelope) {
        TenantId tenant = envelope.tenantId() == null ? null : new TenantId(envelope.tenantId());
        return new ExecutionContext(
                MessageId.random(),
                new TraceId(envelope.traceId()),
                new CorrelationId(envelope.correlationId()),
                new CausationId(envelope.causationId()),
                tenant,
                /* principal */ null,
                /* deadline */ null,
                CancellationToken.create(),
                Map.of());
    }

    /**
     * Default class loader for resolving the payload class on the wire. Tries the calling
     * thread's context class loader first, then falls back to this class's own loader. The
     * PMD {@code UseProperClassLoader} rule is silenced because the context loader IS already
     * the primary path here.
     */
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static ClassLoader currentClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? EventBusRingInboundHandler.class.getClassLoader() : loader;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder. {@code eventBus}, {@code codec}, {@code expectedCodecId} are mandatory. */
    public static final class Builder {

        private EventBus               eventBus;
        private OutboxPayloadCodec     codec;
        private String                 expectedCodecId;
        private @Nullable InboxStorage inbox;
        private String                 consumerId = DEFAULT_CONSUMER_ID;
        private Clock                  clock      = Clock.systemUTC();

        private Builder() {
        }

        public Builder eventBus(EventBus bus) {
            this.eventBus = Objects.requireNonNull(bus, "eventBus");
            return this;
        }

        public Builder codec(OutboxPayloadCodec codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        public Builder expectedCodecId(String id) {
            this.expectedCodecId = Objects.requireNonNull(id, "expectedCodecId");
            return this;
        }

        /**
         * Wire an {@link InboxStorage} so duplicate events from the live + durable paths are
         * filtered at the receiver. When unset the handler dispatches every event — acceptable
         * for cache-invalidation / presence-update flows; required for exactly-once-effective
         * business event delivery.
         */
        public Builder inbox(InboxStorage inbox) {
            this.inbox = Objects.requireNonNull(inbox, "inbox");
            return this;
        }

        public Builder consumerId(String id) {
            this.consumerId = Objects.requireNonNull(id, "consumerId");
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public EventBusRingInboundHandler build() {
            Objects.requireNonNull(eventBus, "eventBus");
            Objects.requireNonNull(codec, "codec");
            Objects.requireNonNull(expectedCodecId, "expectedCodecId");
            return new EventBusRingInboundHandler(this);
        }
    }
}
