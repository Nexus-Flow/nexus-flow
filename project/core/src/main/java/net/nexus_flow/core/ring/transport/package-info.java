/**
 * Ring transport — TCP listener, dialer, per-connection state, optional mTLS. Owns every
 * {@link java.net.Socket} / {@link javax.net.ssl.SSLSocket} lifecycle in the ring.
 *
 * <h2>Threading model (per the {@code nexus-java-network-io-lowlevel} skill, §4)</h2>
 *
 * Blocking I/O + virtual threads — the recommended Java 25 default for connection-oriented
 * protocols where logic is request/response and the connection count is in the hundreds to
 * low-thousands per pod. A selector loop would add state-machine complexity without a
 * proportional benefit at this scale.
 *
 * <ul>
 * <li>ONE platform thread per acceptor — runs {@code ServerSocket.accept()} in a loop. Owned
 * by {@link net.nexus_flow.core.ring.transport.RingAcceptor}, which extends
 * {@link net.nexus_flow.core.runtime.AbstractDaemonWorker} so it inherits the
 * cancel-then-interrupt-then-join shutdown ordering.
 * <li>ONE virtual thread per accepted/dialed connection — runs the read loop, feeds the
 * per-connection {@link net.nexus_flow.core.ring.wire.FrameDecoder}, dispatches every
 * decoded frame to the configured {@link
 * net.nexus_flow.core.ring.transport.RingFrameHandler}.
 * <li>ONE virtual thread per connection for outbound writes, draining a bounded
 * {@code BlockingQueue<RingFrame>}. Sender threads call {@link
 * net.nexus_flow.core.ring.transport.RingConnection#send(net.nexus_flow.core.ring.wire.RingFrame)}
 * — fast enqueue, no I/O. Backpressure surfaces as
 * {@link net.nexus_flow.core.ring.transport.RingBackpressureRejectedException}.
 * </ul>
 *
 * Per skill §3 and §17: one owner per channel, one reader at a time, one writer at a time,
 * bounded outbound queue, close is part of cancellation.
 *
 * <h2>TLS posture</h2>
 *
 * mTLS is the supported production transport — {@link
 * net.nexus_flow.core.ring.transport.RingTlsConfig} carries keystore + truststore paths so the
 * acceptor wraps every accepted socket in {@link javax.net.ssl.SSLSocket} and the dialer
 * (R3) does the same with hostname verification. Per skill §15: client/server mode set before
 * first I/O, hostname verification ON, no SSLEngine (we use the simpler {@code SSLSocket} API
 * because we are using blocking I/O, not a selector loop).
 *
 * <p>For tests and single-process demos, TLS is OPTIONAL: passing {@code null} as
 * {@code RingAcceptorConfig#tlsConfig()} runs plain TCP. This is logged at {@code WARNING}
 * (loud signal in production) and is documented as test-only.
 *
 * <h2>What this package is NOT</h2>
 *
 * No membership, no routing, no frame semantics. The transport is the bytes-in / bytes-out
 * layer; it does not interpret which peer should receive which event. That is the membership
 * (R4) + routing (R7) layer's job.
 */
package net.nexus_flow.core.ring.transport;
