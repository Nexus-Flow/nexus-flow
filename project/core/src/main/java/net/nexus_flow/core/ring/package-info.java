/**
 * In-cluster ring transport for Nexus Flow.
 *
 * <p>The ring is an opt-in transport layer that connects pods of the SAME service version within
 * a single cluster (typically a Kubernetes namespace or a single VM host). It carries:
 *
 * <ul>
 * <li>Domain events fanned out from the {@link net.nexus_flow.core.outbox.OutboxStorage outbox}
 * to all subscribing pods, replacing in-cluster Kafka for the event-bus use case;
 * <li>Cross-pod command and query routing — when the handler for type {@code X} lives on pod
 * {@code B} but the dispatch arrived on pod {@code A}, the ring routes the request
 * transparently with correlation-id round-trip;
 * <li>Distributed saga ownership via lease-based single-writer protocol over the existing
 * {@link net.nexus_flow.core.saga.SagaStorage} contract — sagas survive pod death by
 * lease expiry + CAS-based reassignment.
 * </ul>
 *
 * <h2>What the ring is NOT</h2>
 *
 * <ul>
 * <li>NOT a replacement for Kafka / RabbitMQ / NATS for cross-service or cross-bounded-context
 * integration. The ring is intra-cluster; events crossing a bounded context boundary go
 * through a broker (a future {@code nexus-flow-kafka} adapter publishes ring-local outbox
 * rows to a Kafka topic).
 * <li>NOT a durable log with retention semantics. Per-peer cursors give replay across
 * reconnects, but long-term replay (7-90 days) is the broker's job.
 * <li>NOT exposed outside the cluster — every ring connection runs mTLS with peer certs issued
 * by the same internal CA. There is no public ring port.
 * </ul>
 *
 * <h2>Module layout</h2>
 *
 * <ul>
 * <li>{@link net.nexus_flow.core.ring.wire wire} — binary frame protocol, codec utilities,
 * handshake payloads, protocol exceptions. Pure data and stream parsing — no I/O.
 * <li>{@code transport} (R2/R3) — TCP listener + dialer + mTLS plumbing. Owns the
 * {@code SocketChannel}/{@code SSLSocket} lifecycle.
 * <li>{@code membership} (R4) — SWIM gossip + peer state machine.
 * <li>{@code event} (R5) — outbox-driven fan-out, per-peer cursors, replay protocol.
 * <li>{@code registry} (R6) — distributed handler-location directory.
 * <li>{@code dispatch} (R7) — {@link net.nexus_flow.core.cqrs.command.CommandBus} and
 * {@link net.nexus_flow.core.cqrs.query.QueryBus} ring-backed implementations.
 * <li>{@code saga} (R8) — lease-based saga ownership wrapping {@link
 * net.nexus_flow.core.saga.SagaStorage}.
 * <li>{@code ops} (R9) — operational APIs (disconnect, pause), persisted ring state, metrics.
 * </ul>
 */
package net.nexus_flow.core.ring;
