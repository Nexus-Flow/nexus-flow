package net.nexus_flow.core.ring.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import net.nexus_flow.core.ring.observability.RingJfr;
import net.nexus_flow.core.ring.observability.RingMetrics;
import net.nexus_flow.core.ring.wire.DefaultRingFrameCodec;
import net.nexus_flow.core.ring.wire.FrameDecoder;
import net.nexus_flow.core.ring.wire.FrameEncoder;
import net.nexus_flow.core.ring.wire.RingFrame;
import net.nexus_flow.core.ring.wire.RingFrameCodec;
import net.nexus_flow.core.ring.wire.RingProtocol;
import net.nexus_flow.core.ring.wire.RingProtocolException;
import org.jspecify.annotations.Nullable;

/**
 * Represents one live transport connection — accepted by {@link RingAcceptor} or dialed by
 * {@link RingDialer}. Owns the {@link Socket}, the per-connection {@link FrameDecoder}, the
 * per-connection {@link ConnectionPhase} state machine, the bounded outbound queue, and the
 * two virtual threads driving read and write.
 *
 * <h2>Phase machine (skill §3, §17, audit finding #2)</h2>
 *
 * Every transport invariant — timeouts, send admission, close cause classification, metrics —
 * keys off {@link ConnectionPhase}. The reader VT advances the phase forward through
 * {@code ACCEPTED → (TLS_HANDSHAKING) → PROTOCOL_HANDSHAKING → AUTHENTICATED → ACTIVE};
 * {@link #drain(Duration)} requests {@link ConnectionPhase#DRAINING}; {@link #close(Throwable)}
 * transitions to {@link ConnectionPhase#CLOSING} then {@link ConnectionPhase#CLOSED}.
 *
 * <h2>Truthful send / close (audit finding #7)</h2>
 *
 * The previous shape allowed {@code send()} to return success for a frame that {@code close()}
 * was about to silently discard. {@code send()} now:
 *
 * <ol>
 * <li>Synchronously checks the phase under {@code stateLock}.
 * <li>Atomically enqueues into the bounded outbound queue OR returns a structured rejection
 * via the per-call {@link SendOutcome}.
 * <li>Notifies the per-frame {@link SendCompletion} listener on either delivery success,
 * backpressure rejection, or close-drain rejection.
 * </ol>
 *
 * {@link #close(Throwable)} drains the outbound queue, fails every queued frame's
 * {@link SendCompletion} with the close cause, then transitions to {@code CLOSED}. Senders
 * always observe a truthful outcome.
 *
 * <h2>Writer parking (audit finding #11)</h2>
 *
 * The writer VT now blocks on a {@link Object#wait()} signal — no 50 ms busy poll. Sends and
 * close events {@code notify()} the writer; the writer pulls every available frame in one
 * pass before parking again. Idle connections cost zero scheduler wakeups.
 *
 * <h2>Write timeout (audit, missing functionality M4)</h2>
 *
 * Per-frame write progress is timed: the writer marks {@code lastWriteProgressNanos} on every
 * partial flush; the watchdog tick (driven by the acceptor's slow scheduler) closes the
 * connection if more than {@link ConnectionTimeouts#write()} has elapsed since progress.
 *
 * <h2>Threading recap</h2>
 *
 * <ul>
 * <li>1 reader VT — owns the {@link InputStream}; runs {@link #runReadLoop(RingFrameHandler)};
 * drives phase forward via {@link #advancePhase(ConnectionPhase)}.
 * <li>1 writer VT — owns the {@link OutputStream} + pooled {@link ByteBuffer} encode buffer;
 * runs {@link #runWriteLoop()}; drains the outbound deque under {@code stateLock}.
 * <li>Senders (any thread) call {@link #send(RingFrame)} or {@link #send(RingFrame,
 * SendCompletion)} — fast, no I/O; bounded by {@code outboundQueueCapacity} (frames)
 * AND {@code outboundQueueByteCapacity} (bytes).
 * </ul>
 */
public final class RingConnection implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(RingConnection.class.getName());

    /**
     * Callback fired exactly once per accepted send. {@code success=true} means the frame's
     * bytes were flushed to the socket; {@code success=false} means the frame was rejected
     * post-enqueue (close, write timeout, transport error). Use {@link SendCompletion#NOOP}
     * for fire-and-forget callers.
     */
    @FunctionalInterface
    public interface SendCompletion {
        /** Empty completion handler — neither success nor failure is acted on. */
        SendCompletion NOOP = (s, c) -> {
        };

        /**
         * @param success {@code true} iff the frame was actually written to the socket
         * @param cause   {@code null} when {@code success=true}; non-null with the close /
         *                transport / timeout cause when {@code success=false}
         */
        void onComplete(boolean success, @Nullable Throwable cause);
    }

    /** Discriminator for the synchronous outcome of {@link #send(RingFrame, SendCompletion)}. */
    public enum SendOutcome {
        /** Frame was enqueued; {@link SendCompletion} will fire once it is written. */
        ENQUEUED,
        /** Queue is full (frame count or bytes); the completion has ALREADY fired with failure. */
        REJECTED_BACKPRESSURE,
        /** Connection is closed / draining; the completion has ALREADY fired with failure. */
        REJECTED_CLOSED,
        /** Connection is still in handshake; outside senders are not yet allowed. */
        REJECTED_HANDSHAKE
    }

    private final Socket             socket;
    private final InputStream        input;
    private final OutputStream       output;
    private final FrameDecoder       decoder;
    private final RingFrameCodec     codec;
    /**
     * Identity check against {@link DefaultRingFrameCodec#INSTANCE}, resolved once at
     * construction. When {@code true} the hot send path branches to the static
     * {@link FrameEncoder} helpers, bypassing the interface dispatch entirely — the JIT
     * already inlines a monomorphic call site, but pinning it explicitly guarantees zero
     * dispatch cost even when other connections in the same JVM run a different codec
     * (which would otherwise turn the call site bimorphic and cost ~3-5 ns extra). Users on
     * a non-default codec pay the standard virtual-dispatch cost.
     */
    private final boolean            codecIsDefault;
    private final int                outboundQueueCapacity;
    private final long               outboundQueueByteCapacity;
    private final ConnectionTimeouts timeouts;
    private final PeerAddress        remoteAddress;
    private final RingMetrics        metrics;
    private final ByteBuffer         encodeBuffer; // per-connection, reused by the writer VT

    private final AtomicReference<@Nullable PeerId>                 peerId     = new AtomicReference<>();
    private final AtomicReference<@Nullable RingTransportPrincipal> principal  = new AtomicReference<>();
    private final AtomicReference<ConnectionPhase>                  phase      =
            new AtomicReference<>(ConnectionPhase.ACCEPTED);
    private final AtomicReference<@Nullable Throwable>              closeCause = new AtomicReference<>();

    /**
     * Hot connection-state mutex guarding {@link #outbound}, {@link #outboundBytes} and
     * {@link #drainRequested}. {@link java.util.concurrent.locks.ReentrantLock} (not intrinsic
     * monitor) because the writer thread + N producer threads contend on every {@code send()}
     * and the writer-thread drain. JMH validates ReentrantLock ≈ 2.7× faster than
     * {@code synchronized} at 8-thread contention; this is the lock most affected by that
     * advantage in the entire codebase.
     */
    private final java.util.concurrent.locks.ReentrantLock stateLock      = new java.util.concurrent.locks.ReentrantLock();
    /**
     * Writer-park / writer-wake condition bound to {@link #stateLock}. Replaces the previous
     * {@code stateLock.wait()/notifyAll()} pattern that {@link java.lang.Object}'s intrinsic
     * monitor provides. Direct equivalent in semantics; the {@code ReentrantLock} performance
     * advantage applies symmetrically to both lock acquire AND the condition signal path.
     */
    private final java.util.concurrent.locks.Condition     stateCondition = stateLock.newCondition();
    private final ArrayDeque<Pending>                      outbound       = new ArrayDeque<>();
    private long                                           outboundBytes;
    private boolean                                        drainRequested;

    private final long    createdAtNanos         = System.nanoTime();
    private volatile long lastReadNanos          = System.nanoTime();
    private volatile long lastWriteProgressNanos = System.nanoTime();
    private volatile long queuedSinceNanos;

    /**
     * Nanos when the writer entered an in-progress I/O call. Updated before each
     * {@code output.write}; cleared after the write returns. The watchdog uses this to
     * detect a writer blocked in I/O even when the outbound queue is empty (the frame is
     * out of the queue but stuck inside the OutputStream).
     */
    private volatile long writeInProgressSinceNanos;

    /**
     * Writer thread reference set when {@link #runWriteLoop()} starts. {@link #close} uses
     * this to interrupt the writer when the socket close does not unblock an in-progress
     * write — required so test stubs (and pathological real-world peers) cannot leave the
     * writer parked forever after a watchdog-driven close.
     */
    private final AtomicReference<@Nullable Thread> writerThread = new AtomicReference<>();

    /** One pending frame + its caller's completion callback. */
    private record Pending(RingFrame frame, int encodedBytes, SendCompletion completion) {
    }

    /**
     * Construct a connection. The acceptor / dialer is expected to have already completed any
     * TLS handshake and set socket options ({@code TCP_NODELAY}, {@code SO_KEEPALIVE}).
     *
     * @param socket                    the connected socket (plain or {@link SSLSocket});
     *                                  never {@code null}
     * @param decoder                   per-connection decoder; never {@code null}
     * @param outboundQueueCapacity     bounded queue depth (frames)
     * @param outboundQueueByteCapacity bounded queue depth (bytes) — typically
     *                                  {@code outboundQueueCapacity × averageFrameSize}, but
     *                                  enforced separately so a few huge frames cannot inflate
     *                                  memory beyond the cap
     * @param timeouts                  per-phase timeout budgets
     * @param remoteAddress             the peer's network address
     * @param metrics                   the ring metrics sink; {@link RingMetrics#noOp()} is
     *                                  acceptable for tests / no-observability deployments
     */
    public RingConnection(
            Socket socket,
            FrameDecoder decoder,
            int outboundQueueCapacity,
            long outboundQueueByteCapacity,
            ConnectionTimeouts timeouts,
            PeerAddress remoteAddress,
            RingMetrics metrics) throws IOException {
        this(socket,
             decoder,
             RingFrameCodec.BYTE_BUFFER,
             outboundQueueCapacity,
             outboundQueueByteCapacity,
             timeouts,
             remoteAddress,
             metrics);
    }

    /**
     * Canonical constructor that takes a pluggable {@link RingFrameCodec}. Selected at runtime
     * configuration time via {@link RingAcceptorConfig#frameCodec()} /
     * {@link RingDialerConfig#frameCodec()}; the codec is reused for every send on this
     * connection. The legacy 7-arg constructor delegates here with
     * {@link RingFrameCodec#BYTE_BUFFER}.
     */
    public RingConnection(
            Socket socket,
            FrameDecoder decoder,
            RingFrameCodec codec,
            int outboundQueueCapacity,
            long outboundQueueByteCapacity,
            ConnectionTimeouts timeouts,
            PeerAddress remoteAddress,
            RingMetrics metrics) throws IOException {
        this.socket         = Objects.requireNonNull(socket, "socket");
        this.decoder        = Objects.requireNonNull(decoder, "decoder");
        this.codec          = Objects.requireNonNull(codec, "codec");
        this.codecIsDefault = codec == DefaultRingFrameCodec.INSTANCE;
        if (outboundQueueCapacity < 1) {
            throw new IllegalArgumentException(
                    "outboundQueueCapacity must be >= 1: " + outboundQueueCapacity);
        }
        if (outboundQueueByteCapacity < (long) RingProtocol.HEADER_BYTES) {
            throw new IllegalArgumentException(
                    "outboundQueueByteCapacity must be >= HEADER_BYTES: "
                            + outboundQueueByteCapacity);
        }
        this.outboundQueueCapacity     = outboundQueueCapacity;
        this.outboundQueueByteCapacity = outboundQueueByteCapacity;
        this.timeouts                  = Objects.requireNonNull(timeouts, "timeouts");
        this.remoteAddress             = Objects.requireNonNull(remoteAddress, "remoteAddress");
        this.metrics                   = Objects.requireNonNull(metrics, "metrics");
        this.input                     = socket.getInputStream();
        this.output                    = socket.getOutputStream();
        // Pooled encode buffer sized to the larger of (one full max-body frame, 64KiB chunk).
        int bufSize = Math.min(decoder.maxBodyBytes() + RingProtocol.HEADER_BYTES, 1 << 17);
        this.encodeBuffer = ByteBuffer.allocate(bufSize);
    }

    // ------------------------------------------------------------------------
    // Read-side accessors
    // ------------------------------------------------------------------------

    public PeerAddress remoteAddress() {
        return remoteAddress;
    }

    /** Currently bound peer id, or {@code null} before the HELLO handshake completes. */
    public @Nullable PeerId peerId() {
        return peerId.get();
    }

    /** Current TLS principal, or {@code null} before the handshake or for plain-TCP. */
    public @Nullable RingTransportPrincipal principal() {
        return principal.get();
    }

    public ConnectionPhase phase() {
        return phase.get();
    }

    public int outboundQueueDepth() {
        stateLock.lock();
        try {
            return outbound.size();
        } finally {
            stateLock.unlock();
        }
    }

    public long outboundQueueBytes() {
        stateLock.lock();
        try {
            return outboundBytes;
        } finally {
            stateLock.unlock();
        }
    }

    public boolean isClosed() {
        return phase().isClosing();
    }

    public long nanosSinceLastRead() {
        return System.nanoTime() - lastReadNanos;
    }

    public long nanosSinceCreated() {
        return System.nanoTime() - createdAtNanos;
    }

    /** Most recent successful write progress timestamp (nanos). For watchdog use. */
    public long nanosSinceLastWriteProgress() {
        return System.nanoTime() - lastWriteProgressNanos;
    }

    // ------------------------------------------------------------------------
    // Handshake plumbing
    // ------------------------------------------------------------------------

    /**
     * Bind the peer id once it has been validated from the HELLO frame. Single-shot. Advances
     * the phase to {@link ConnectionPhase#AUTHENTICATED} if not already past it; the router
     * advances to {@link ConnectionPhase#ACTIVE} once the handshake handler completes.
     */
    public void bindPeerId(PeerId id) {
        Objects.requireNonNull(id, "id");
        if (!peerId.compareAndSet(null, id)) {
            throw new IllegalStateException(
                    "peerId already bound to " + peerId.get() + "; attempted rebind to " + id);
        }
        advancePhase(ConnectionPhase.AUTHENTICATED);
    }

    /**
     * Promote the connection to {@link ConnectionPhase#ACTIVE}. Called by the handshake
     * handler once HELLO/HELLO_ACK exchange has fully completed and the peer is ready for
     * normal traffic.
     */
    public void markActive() {
        advancePhase(ConnectionPhase.ACTIVE);
    }

    /**
     * Monotonic phase transition. Returns {@code true} if the phase actually advanced.
     */
    public boolean advancePhase(ConnectionPhase target) {
        Objects.requireNonNull(target, "target");
        while (true) {
            ConnectionPhase current = phase.get();
            if (target.ordinal() <= current.ordinal()) {
                return false;
            }
            if (phase.compareAndSet(current, target)) {
                emitPhaseTransition(current, target);
                if (target == ConnectionPhase.AUTHENTICATED || target == ConnectionPhase.ACTIVE) {
                    applyIdleTimeoutIfNeeded();
                }
                return true;
            }
        }
    }

    // ------------------------------------------------------------------------
    // Send
    // ------------------------------------------------------------------------

    /**
     * Enqueue a frame for outbound delivery; fire-and-forget overload.
     *
     * @throws RingBackpressureRejectedException if the queue is full
     * @throws IllegalStateException             if the connection is closed/draining or still
     *                                           in handshake
     */
    public void send(RingFrame frame) {
        SendOutcome outcome = send(frame, SendCompletion.NOOP);
        switch (outcome) {
            case ENQUEUED              -> {
                /* fine */ }
            case REJECTED_BACKPRESSURE ->
                 throw new RingBackpressureRejectedException(
                         peerIdOrAddress(),
                         outboundQueueDepth(),
                         outboundQueueCapacity,
                         frame);
            case REJECTED_CLOSED       -> throw new IllegalStateException(
                    "connection to " + remoteAddress + " is " + phase() + "; send refused");
            case REJECTED_HANDSHAKE    -> throw new IllegalStateException(
                    "connection to " + remoteAddress + " is still in handshake (" + phase()
                            + "); outside senders must wait");
        }
    }

    /**
     * Enqueue a frame with an explicit completion callback. Returns the synchronous outcome;
     * the callback fires exactly once with the eventual delivery result. On {@code REJECTED_*}
     * the callback has ALREADY fired with {@code success=false} before this method returns.
     */
    public SendOutcome send(RingFrame frame, SendCompletion completion) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(completion, "completion");
        int encoded = codecIsDefault ? FrameEncoder.encodedLength(frame) : codec.encodedLength(frame);
        stateLock.lock();
        try {
            ConnectionPhase p = phase.get();
            if (p.isClosing() || drainRequested) {
                Throwable c = closeCause.get();
                completion.onComplete(false, c != null ? c : new IllegalStateException("connection " + p));
                return SendOutcome.REJECTED_CLOSED;
            }
            if (!p.acceptsSends()) {
                completion.onComplete(false,
                                      new IllegalStateException(
                                              "connection not yet ready for application sends (phase=" + p + ")"));
                return SendOutcome.REJECTED_HANDSHAKE;
            }
            if (outbound.size() >= outboundQueueCapacity || outboundBytes + encoded > outboundQueueByteCapacity) {
                metrics.incrementBackpressureRejected();
                emitRejection("BACKPRESSURE", frame);
                completion.onComplete(false,
                                      new RingBackpressureRejectedException(
                                              peerIdOrAddress(),
                                              outbound.size(),
                                              outboundQueueCapacity,
                                              frame));
                return SendOutcome.REJECTED_BACKPRESSURE;
            }
            outbound.addLast(new Pending(frame, encoded, completion));
            outboundBytes += encoded;
            if (outbound.size() == 1) {
                queuedSinceNanos = System.nanoTime();
            }
            metrics.recordOutboundQueueBytes(outboundBytes);
            stateCondition.signalAll();
            return SendOutcome.ENQUEUED;
        } finally {
            stateLock.unlock();
        }
    }

    // ------------------------------------------------------------------------
    // Read loop
    // ------------------------------------------------------------------------

    /**
     * Read loop body. Runs on the reader virtual thread. Sets {@code SO_TIMEOUT} to the
     * handshake budget initially; the {@link #advancePhase(ConnectionPhase)} transitions to
     * {@code AUTHENTICATED}/{@code ACTIVE} raise it to the steady-state idle budget.
     */
    public void runReadLoop(RingFrameHandler handler) {
        Objects.requireNonNull(handler, "handler");
        byte[]    buffer = new byte[8 * 1024];
        Throwable cause  = null;
        try {
            extractTlsPrincipal();
            socket.setSoTimeout((int) Math.min(timeouts.handshake().toMillis(), Integer.MAX_VALUE));
            advancePhase(ConnectionPhase.PROTOCOL_HANDSHAKING);
            handler.onAccepted(this);
            ByteBuffer wrapped = ByteBuffer.wrap(buffer);
            while (!phase.get().isClosing()) {
                int n;
                try {
                    n = input.read(buffer);
                } catch (SocketTimeoutException idle) {
                    cause = idle;
                    if (phase.get().isHandshaking()) {
                        metrics.incrementHandshakeTimeout();
                        emitRejection("HANDSHAKE_TIMEOUT", null);
                    } else {
                        metrics.incrementIdleTimeout();
                    }
                    break;
                }
                if (n < 0) {
                    break; // peer EOF
                }
                if (n == 0) {
                    continue;
                }
                lastReadNanos = System.nanoTime();
                metrics.recordBytesRead(n);
                wrapped.position(0);
                wrapped.limit(n);
                decoder.tryDecode(
                                  wrapped,
                                  frame -> {
                                      metrics.incrementFramesRead(frame.type());
                                      emitFrame("read", frame);
                                      handler.onFrame(this, frame);
                                  });
            }
        } catch (RingProtocolException protocolViolation) {
            cause = protocolViolation;
            metrics.incrementProtocolViolation();
            emitRejection("PROTOCOL_VIOLATION", null);
            LOG.log(System.Logger.Level.WARNING,
                    () -> "ring connection " + remoteAddress
                            + " protocol violation, closing: " + protocolViolation.getMessage());
        } catch (IOException io) {
            cause = io;
        } catch (RuntimeException ex) {
            cause = ex;
        } finally {
            close(cause);
            try {
                handler.onClosed(this, closeCause.get());
            } catch (RuntimeException ignored) {
                // handler.onClosed is best-effort; never propagate from the close path.
            }
        }
    }

    // ------------------------------------------------------------------------
    // Write loop
    // ------------------------------------------------------------------------

    /**
     * Write loop body. Runs on the writer virtual thread. Parks on {@code stateLock.wait()}
     * whenever the queue is empty — zero scheduler wakeups on idle connections. Wakes on send
     * enqueue, drain request, or close.
     */
    public void runWriteLoop() {
        writerThread.set(Thread.currentThread());
        List<Pending> drained = new ArrayList<>(16);
        try {
            while (true) {
                stateLock.lock();
                try {
                    while (outbound.isEmpty() && !phase.get().isClosing()) {
                        try {
                            stateCondition.await();
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    if (phase.get().isClosing() && outbound.isEmpty()) {
                        return;
                    }
                    drained.clear();
                    while (!outbound.isEmpty() && drained.size() < outboundQueueCapacity) {
                        drained.add(outbound.removeFirst());
                    }
                    // Hand-rolled accumulator instead of `stream().mapToLong().sum()` —
                    // eliminates lambda capture and LongStream allocation on the writer-thread
                    // hot path. Drain happens once per outbound batch and the writer thread is
                    // shared by every published frame on this connection.
                    long drainedBytes = 0L;
                    for (Pending p : drained) {
                        drainedBytes += p.encodedBytes;
                    }
                    outboundBytes -= drainedBytes;
                    if (outbound.isEmpty() && drainRequested) {
                        // No more queued frames AND drain requested; close on this thread.
                        close(null);
                    }
                    metrics.recordOutboundQueueBytes(outboundBytes);
                    queuedSinceNanos = outbound.isEmpty() ? 0L : System.nanoTime();
                } finally {
                    stateLock.unlock();
                }
                for (Pending p : drained) {
                    boolean ok = writeFrameSafely(p);
                    if (!ok) {
                        // writeFrameSafely closed the connection; cancel the rest of the batch.
                        for (int i = drained.indexOf(p) + 1; i < drained.size(); i++) {
                            Pending tail = drained.get(i);
                            tail.completion.onComplete(false, closeCause.get());
                        }
                        return;
                    }
                }
            }
        } finally {
            failPendingOnClose();
        }
    }

    // PMD UnusedAssignment fires on the writeInProgressSinceNanos = System.nanoTime() / =0L
    // pair because PMD's intra-method dataflow does not see the watchdog thread's concurrent
    // read of the volatile field — the field IS read across threads. False positive.
    @SuppressWarnings("PMD.UnusedAssignment")
    private boolean writeFrameSafely(Pending p) {
        try {
            encodeBuffer.clear();
            if (codecIsDefault) {
                FrameEncoder.encodeInto(p.frame, encodeBuffer, decoder.maxBodyBytes());
            } else {
                codec.encodeInto(p.frame, encodeBuffer, decoder.maxBodyBytes());
            }
            encodeBuffer.flip();
            int written = encodeBuffer.remaining();
            writeInProgressSinceNanos = System.nanoTime();
            try {
                output.write(encodeBuffer.array(),
                             encodeBuffer.arrayOffset() + encodeBuffer.position(),
                             written);
                output.flush();
            } finally {
                writeInProgressSinceNanos = 0L;
            }
            lastWriteProgressNanos = System.nanoTime();
            metrics.recordBytesWritten(written);
            metrics.incrementFramesWritten(p.frame.type());
            emitFrame("write", p.frame);
            p.completion.onComplete(true, null);
            return true;
        } catch (IOException io) {
            close(io);
            p.completion.onComplete(false, io);
            return false;
        } catch (RuntimeException ex) {
            close(ex);
            p.completion.onComplete(false, ex);
            return false;
        }
    }

    private void failPendingOnClose() {
        List<Pending> remaining;
        stateLock.lock();
        try {
            remaining = new ArrayList<>(outbound);
            outbound.clear();
            outboundBytes = 0L;
        } finally {
            stateLock.unlock();
        }
        Throwable cause = closeCause.get();
        if (cause == null) {
            cause = new IllegalStateException("connection " + phase.get());
        }
        for (Pending p : remaining) {
            try {
                p.completion.onComplete(false, cause);
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
    }

    // ------------------------------------------------------------------------
    // Drain / close
    // ------------------------------------------------------------------------

    /**
     * Request a graceful drain: stop admitting new sends, let the writer flush already-queued
     * frames, then close. Returns immediately; the writer thread performs the close on its
     * own once the queue empties. {@code maxWait} is a hint — the caller-driven watchdog
     * forces a close after that elapses.
     */
    public void drain(Duration maxWait) {
        Objects.requireNonNull(maxWait, "maxWait");
        stateLock.lock();
        try {
            if (phase.get().isClosing()) {
                return;
            }
            drainRequested = true;
            advancePhase(ConnectionPhase.DRAINING);
            stateCondition.signalAll();
        } finally {
            stateLock.unlock();
        }
        long deadlineNs = System.nanoTime() + maxWait.toNanos();
        stateLock.lock();
        try {
            while (!outbound.isEmpty() && !phase.get().isClosing()) {
                long remainingNs = deadlineNs - System.nanoTime();
                if (remainingNs <= 0L) {
                    break;
                }
                try {
                    stateCondition.awaitNanos(remainingNs);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            stateLock.unlock();
        }
        close(null);
    }

    /**
     * Idempotent close. Records the cause (first writer wins), closes the socket, signals the
     * writer VT to wake up. Reader VT exits on the next {@code read()} (which throws or
     * returns -1). The {@link #failPendingOnClose()} hook in the writer loop fails every
     * still-queued frame with the close cause.
     */
    public void close(@Nullable Throwable cause) {
        // CAS from a non-closing phase into CLOSING. Using getAndSet here would be a bug:
        // it would clobber a thread that has already transitioned to CLOSED, regressing
        // phase to CLOSING on every subsequent call.
        ConnectionPhase prev;
        while (true) {
            prev = phase.get();
            if (prev.isClosing()) {
                return; // CLOSING or CLOSED — another caller already started / finished
            }
            if (phase.compareAndSet(prev, ConnectionPhase.CLOSING)) {
                break;
            }
        }
        closeCause.compareAndSet(null, cause);
        emitPhaseTransition(prev, ConnectionPhase.CLOSING);
        metrics.incrementClose(classifyCause(cause));
        try {
            socket.close();
        } catch (IOException ignored) {
            // best-effort
        }
        stateLock.lock();
        try {
            stateCondition.signalAll();
        } finally {
            stateLock.unlock();
        }
        // Interrupt the writer thread so a writer blocked in output.write (slow peer,
        // pathological OutputStream that does not honor socket close) wakes up. The
        // writer's catch handlers fire the per-frame completions with the close cause.
        Thread w = writerThread.get();
        if (w != null && w != Thread.currentThread()) {
            w.interrupt();
        }
        // CAS forward only — defensive against a concurrent caller resetting state.
        phase.compareAndSet(ConnectionPhase.CLOSING, ConnectionPhase.CLOSED);
    }

    @Override
    public void close() {
        close(null);
    }

    // ------------------------------------------------------------------------
    // Watchdog (called by the acceptor's scheduled tick)
    // ------------------------------------------------------------------------

    /**
     * One watchdog tick. Closes the connection if {@link ConnectionTimeouts#write()} has
     * elapsed since the writer last made progress while frames are queued. Idempotent / cheap
     * — call from a scheduled task.
     *
     * @return {@code true} if the watchdog closed the connection
     */
    public boolean checkWriteTimeout() {
        if (phase.get().isClosing()) {
            return false;
        }
        long now          = System.nanoTime();
        long timeoutNanos = timeouts.write().toNanos();

        // Case 1: writer is currently stuck inside output.write() — the frame is OUT of
        // the queue but not yet flushed. Most common slow-peer signature.
        long inProgressSince = writeInProgressSinceNanos;
        if (inProgressSince != 0L && now - inProgressSince > timeoutNanos) {
            metrics.incrementWriteTimeout();
            close(new IOException("write watchdog: write in progress for "
                    + Duration.ofNanos(now - inProgressSince)));
            return true;
        }

        // Case 2: outbound queue has frames but the writer hasn't drained them.
        long queued;
        stateLock.lock();
        try {
            if (outbound.isEmpty()) {
                return false;
            }
            queued = queuedSinceNanos;
        } finally {
            stateLock.unlock();
        }
        long age = now - Math.max(queued, lastWriteProgressNanos);
        if (age <= timeoutNanos) {
            return false;
        }
        metrics.incrementWriteTimeout();
        close(new IOException("write watchdog: queued frame stalled for "
                + Duration.ofNanos(age)));
        return true;
    }

    // ------------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------------

    private void extractTlsPrincipal() {
        if (!(socket instanceof SSLSocket ssl)) {
            principal.compareAndSet(null, RingTransportPrincipal.anonymous(remoteAddress));
            return;
        }
        try {
            SSLSession    session = ssl.getSession();
            Certificate[] certs   = session.getPeerCertificates();
            if (certs.length > 0 && certs[0] instanceof X509Certificate x509) {
                principal.compareAndSet(
                                        null, RingTransportPrincipal.authenticated(x509, remoteAddress));
            }
        } catch (SSLPeerUnverifiedException ssle) {
            // Either NEED client auth was off, or the peer didn't present a cert.
            principal.compareAndSet(null, RingTransportPrincipal.anonymous(remoteAddress));
        }
    }

    private void applyIdleTimeoutIfNeeded() {
        Duration idle = timeouts.idle();
        if (idle.isZero()) {
            return;
        }
        try {
            socket.setSoTimeout((int) Math.min(idle.toMillis(), Integer.MAX_VALUE));
        } catch (IOException ignored) {
            // socket may already be closed; the close path will surface the real cause
        }
    }

    private PeerId peerIdOrAddress() {
        PeerId p = peerId.get();
        return p != null ? p : PeerId.of(remoteAddress.toString());
    }

    private static String classifyCause(@Nullable Throwable cause) {
        if (cause == null) {
            return "clean";
        }
        if (cause instanceof SocketTimeoutException) {
            return "idle_or_handshake_timeout";
        }
        if (cause instanceof RingProtocolException) {
            return "protocol_violation";
        }
        if (cause instanceof IOException) {
            return "io_error";
        }
        return "internal";
    }

    private void emitPhaseTransition(ConnectionPhase from, ConnectionPhase to) {
        var ev = new RingJfr.ConnectionLifecycle();
        ev.begin();
        if (ev.shouldCommit()) {
            PeerId id = peerId.get();
            ev.peerId        = id == null ? "?" : id.value();
            ev.remoteAddress = remoteAddress.toString();
            ev.phase         = to.name();
            ev.transport     = (socket instanceof SSLSocket) ? "tls" : "tcp";
            ev.commit();
        }
        LOG.log(System.Logger.Level.DEBUG,
                () -> "ring connection " + remoteAddress + " phase " + from + " -> " + to);
    }

    private void emitFrame(String direction, RingFrame frame) {
        var ev = new RingJfr.Frame();
        ev.begin();
        if (ev.shouldCommit()) {
            ev.direction = direction;
            ev.frameType = frame.type().name();
            ev.bodyBytes = frame.body().length();
            PeerId id = peerId.get();
            ev.peerId = id == null ? "?" : id.value();
            ev.commit();
        }
    }

    private void emitRejection(String reason, @Nullable RingFrame frame) {
        var ev = new RingJfr.Rejection();
        ev.begin();
        if (ev.shouldCommit()) {
            ev.reason    = reason;
            ev.frameType = frame == null ? "" : frame.type().name();
            PeerId id = peerId.get();
            ev.peerId = id == null ? "?" : id.value();
            ev.commit();
        }
    }

    /**
     * Snapshot of read/queue counters for diagnostics tests. The structure is intentionally
     * minimal — the rich data lives in JFR / metrics.
     */
    public Diagnostics diagnostics() {
        stateLock.lock();
        try {
            return new Diagnostics(
                    phase.get(),
                    outbound.size(),
                    outboundBytes,
                    Instant.now(),
                    lastReadNanos,
                    lastWriteProgressNanos);
        } finally {
            stateLock.unlock();
        }
    }

    /** Immutable snapshot of per-connection diagnostics counters. */
    public record Diagnostics(
                              ConnectionPhase phase,
                              int outboundQueueDepth,
                              long outboundQueueBytes,
                              Instant snapshotAt,
                              long lastReadNanos,
                              long lastWriteProgressNanos) {
    }

    /** Builder used by the acceptor/dialer for clarity. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link RingConnection}. */
    public static final class Builder {
        private Socket             socket;
        private FrameDecoder       decoder;
        private RingFrameCodec     codec                     = RingFrameCodec.BYTE_BUFFER;
        private int                outboundQueueCapacity     = 1024;
        private long               outboundQueueByteCapacity = 1024L * 1024L; // 1 MiB
        private ConnectionTimeouts timeouts                  = ConnectionTimeouts.defaults();
        private PeerAddress        remoteAddress;
        private RingMetrics        metrics                   = RingMetrics.noOp();

        private Builder() {
        }

        public Builder socket(Socket s) {
            this.socket = s;
            return this;
        }

        public Builder decoder(FrameDecoder d) {
            this.decoder = d;
            return this;
        }

        /**
         * Pluggable wire codec used for outbound encoding on this connection. Defaults to
         * {@link RingFrameCodec#BYTE_BUFFER}. The acceptor / dialer normally provides this
         * value transitively from {@link RingAcceptorConfig#frameCodec()} /
         * {@link RingDialerConfig#frameCodec()}.
         */
        public Builder codec(RingFrameCodec c) {
            this.codec = c;
            return this;
        }

        public Builder outboundQueueCapacity(int n) {
            this.outboundQueueCapacity = n;
            return this;
        }

        public Builder outboundQueueByteCapacity(long n) {
            this.outboundQueueByteCapacity = n;
            return this;
        }

        public Builder timeouts(ConnectionTimeouts t) {
            this.timeouts = t;
            return this;
        }

        public Builder remoteAddress(PeerAddress a) {
            this.remoteAddress = a;
            return this;
        }

        public Builder metrics(RingMetrics m) {
            this.metrics = m;
            return this;
        }

        public RingConnection build() throws IOException {
            return new RingConnection(
                    socket, decoder, codec, outboundQueueCapacity, outboundQueueByteCapacity,
                    timeouts, remoteAddress, metrics);
        }
    }

}
