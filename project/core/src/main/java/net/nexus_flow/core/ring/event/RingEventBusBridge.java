package net.nexus_flow.core.ring.event;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.nexus_flow.core.cqrs.event.AbstractDomainEventListener;
import net.nexus_flow.core.cqrs.event.EventBus;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.outbox.OutboxPayloadCodec;
import net.nexus_flow.core.ring.membership.MembershipRegistry;
import net.nexus_flow.core.ring.membership.PeerState;
import net.nexus_flow.core.ring.observability.RingMetrics;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.transport.RingConnection;
import net.nexus_flow.core.ring.transport.RingConnectionRegistry;
import net.nexus_flow.core.ring.wire.FrameType;
import net.nexus_flow.core.ring.wire.RingFrame;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowScope;
import net.nexus_flow.core.runtime.ids.FastUuid;

/**
 * Low-latency, best-effort fan-out of every domain event published through the local
 * {@link EventBus} to live ring peers. The complement of {@link RingOutboxBridge}:
 *
 * <ul>
 * <li>{@code RingOutboxBridge} drives DURABLE fan-out from the outbox — at-least-once,
 * higher latency, survives crashes.
 * <li>{@code RingEventBusBridge} drives LIVE fan-out from the bus — best-effort, lower
 * latency, lost if the receiver is unreachable at the moment of publish.
 * </ul>
 *
 * <h2>Wiring patterns</h2>
 *
 * <ol>
 * <li><strong>Live-only:</strong> only the bus bridge is installed. Use when events are
 * informational (cache invalidations, presence updates) and a peer that misses one
 * can self-recover from its own state.
 * <li><strong>Durable-only:</strong> only the outbox bridge is installed. Use when every
 * event must reach every peer, latency is secondary.
 * <li><strong>Both:</strong> bus bridge for low-latency notifications + outbox bridge for
 * at-least-once recovery. Receivers MUST deduplicate via {@link
 * net.nexus_flow.core.inbox.InboxStorage} so the duplicate from the durable path is
 * skipped after the live path already delivered. The bridge logs a startup warning
 * to surface this requirement.
 * </ol>
 *
 * <h2>Threading</h2>
 *
 * The bridge registers a single {@link AbstractDomainEventListener} on the bus; the
 * listener runs synchronously on the dispatch thread. Each fan-out is non-blocking — the
 * frame is enqueued via {@link RingConnection#send(RingFrame)} and the dispatch thread
 * returns immediately. Saturated peer queues surface as backpressure rejections (counted
 * via {@link RingMetrics#incrementBackpressureRejected()}) — the bridge does NOT block
 * the publisher.
 *
 * <h2>Context propagation</h2>
 *
 * The bridge reads the current {@link ExecutionContext} from {@link FlowScope#current()}
 * — published events carry the dispatcher's trace / correlation / causation ids in the
 * envelope. If no scope is bound (rare for in-runtime publish; possible from external
 * callers) the bridge falls back to {@link ExecutionContext#root()}.
 */
public final class RingEventBusBridge implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(RingEventBusBridge.class.getName());

    private final PeerId                 localPeerId;
    private final EventBus               eventBus;
    private final RingConnectionRegistry connections;
    private final MembershipRegistry     membership;
    private final OutboxPayloadCodec     codec;
    private final String                 codecId;
    private final RingMetrics            metrics;
    private final AtomicLong             sourceSequence = new AtomicLong();
    private final AtomicBoolean          closed         = new AtomicBoolean();
    private final FanOutListener         listener;

    public RingEventBusBridge(
            PeerId localPeerId,
            EventBus eventBus,
            RingConnectionRegistry connections,
            MembershipRegistry membership,
            OutboxPayloadCodec codec,
            String codecId) {
        this(localPeerId, eventBus, connections, membership, codec, codecId, RingMetrics.noOp());
    }

    public RingEventBusBridge(
            PeerId localPeerId,
            EventBus eventBus,
            RingConnectionRegistry connections,
            MembershipRegistry membership,
            OutboxPayloadCodec codec,
            String codecId,
            RingMetrics metrics) {
        this.localPeerId = Objects.requireNonNull(localPeerId, "localPeerId");
        this.eventBus    = Objects.requireNonNull(eventBus, "eventBus");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.membership  = Objects.requireNonNull(membership, "membership");
        this.codec       = Objects.requireNonNull(codec, "codec");
        this.codecId     = Objects.requireNonNull(codecId, "codecId");
        this.metrics     = Objects.requireNonNull(metrics, "metrics");
        this.listener    = new FanOutListener();
        eventBus.register(listener);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            eventBus.unregister(listener);
        }
    }

    /** Diagnostics for tests — number of events the bridge has observed. */
    public long observedEvents() {
        return sourceSequence.get();
    }

    /**
     * Listener subscribed to every {@link DomainEvent} published locally. Concrete inner
     * class (not anonymous) so the AbstractDomainEventListener's bytecode-driven type
     * resolution sees the {@code DomainEvent} bound on {@code FanOutListener.class}.
     */
    private final class FanOutListener extends AbstractDomainEventListener<DomainEvent> {
        @Override
        public void handle(DomainEvent event) {
            if (closed.get()) {
                return;
            }
            long             sequence = sourceSequence.incrementAndGet();
            ExecutionContext ctx      = FlowScope.current().orElseGet(ExecutionContext::root);
            byte[]           payload;
            try {
                payload = codec.encode(event);
            } catch (RuntimeException encodeFail) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "RingEventBusBridge: failed to encode "
                                + event.getClass().getName() + " for ring fan-out: "
                                + encodeFail.getMessage());
                return;
            }
            UUID              traceId       = ctx.traceId() == null ? FastUuid.v4() : ctx.traceId().value();
            UUID              correlationId = ctx.correlationId() == null ? FastUuid.v4() : ctx.correlationId().value();
            UUID              causationId   = ctx.causationId() == null ? FastUuid.v4() : ctx.causationId().value();
            String            tenantId      = null;
            RingEventEnvelope envelope      = new RingEventEnvelope(
                    localPeerId,
                    sequence,
                    event.eventType(),
                    codecId,
                    traceId,
                    correlationId,
                    causationId,
                    tenantId,
                    payload);
            RingFrame         frame         = RingFrame.wrapping(FrameType.EVENT, envelope.encode());
            for (var peerInfo : membership.peers()) {
                if (peerInfo.state() != PeerState.ALIVE) {
                    continue;
                }
                if (peerInfo.peerId().equals(localPeerId)) {
                    continue;
                }
                RingConnection conn = connections.get(peerInfo.peerId()).orElse(null);
                if (conn == null || conn.isClosed()) {
                    continue;
                }
                try {
                    conn.send(frame);
                    metrics.incrementFramesWritten(FrameType.EVENT);
                } catch (RuntimeException sendFail) {
                    // Best-effort: a saturated peer queue surfaces as a backpressure
                    // rejection at the connection level (counted in RingMetrics); the
                    // bridge intentionally does NOT block the publisher.
                    metrics.incrementBackpressureRejected();
                    LOG.log(System.Logger.Level.DEBUG,
                            () -> "RingEventBusBridge: send to " + peerInfo.peerId()
                                    + " failed (live path will be retried by outbox bridge if"
                                    + " durable fan-out is enabled): "
                                    + sendFail.getMessage());
                }
            }
        }
    }
}
