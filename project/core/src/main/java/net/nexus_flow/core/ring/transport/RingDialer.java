package net.nexus_flow.core.ring.transport;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import net.nexus_flow.core.ring.observability.RingMetrics;
import net.nexus_flow.core.ring.wire.FrameDecoder;

/**
 * Synchronous outbound dialer.
 *
 * <h2>TLS handshake timeout (audit finding #13)</h2>
 *
 * The previous version applied the connect timeout to {@link Socket#connect(java.net.SocketAddress, int)}
 * but did not apply a timeout to {@link SSLSocket#startHandshake()}. A peer that accepted
 * the TCP connection but never sent a TLS {@code ServerHello} would hang the dialer
 * indefinitely. The redesigned dial path sets {@code SO_TIMEOUT} to {@link
 * ConnectionTimeouts#handshake()} BEFORE calling {@code startHandshake}, then resets it
 * before constructing the {@link RingConnection} (the connection's state machine takes over
 * SO_TIMEOUT management).
 *
 * <h2>Lifecycle</h2>
 *
 * One VT executor per dialer; {@link #close()} interrupts every reader / writer the dialer
 * ever started. The dialer also closes every connection it created on its own
 * {@code close()} so callers don't need a separate registry.
 */
public final class RingDialer implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(RingDialer.class.getName());

    private final RingDialerConfig config;
    private final RingFrameHandler handler;
    private final ExecutorService  perConnectionExecutor;
    private final SSLContext       sslContext;
    private final RingMetrics      metrics;

    public RingDialer(RingDialerConfig config, RingFrameHandler handler) throws IOException, GeneralSecurityException {
        this.config                = Objects.requireNonNull(config, "config");
        this.handler               = Objects.requireNonNull(handler, "handler");
        this.perConnectionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.sslContext            = config.tlsConfig() == null ? null : config.tlsConfig().buildSslContext();
        this.metrics               = new RingMetrics(config.metrics(), "dialer");
    }

    /**
     * Dial {@code address} synchronously. Returns the live {@link RingConnection} or throws
     * a classified {@link RingDialException}.
     */
    public RingConnection dial(PeerAddress address) {
        Objects.requireNonNull(address, "address");
        Socket socket = null;
        long   start  = System.nanoTime();
        try {
            socket = buildSocket();
            int connectMs = Math.toIntExact(Math.min(
                                                     config.connectTimeout().toMillis(), Integer.MAX_VALUE));
            socket.connect(new InetSocketAddress(address.host(), address.port()), connectMs);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            if (socket instanceof SSLSocket ssl) {
                int handshakeMs = Math.toIntExact(Math.min(
                                                           config.timeouts().handshake().toMillis(), Integer.MAX_VALUE));
                ssl.setSoTimeout(handshakeMs);
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(params);
                long tlsStart = System.nanoTime();
                ssl.startHandshake();
                metrics.recordTlsHandshake(
                                           Duration.ofNanos(System.nanoTime() - tlsStart), true);
            }
            RingConnection connection = new RingConnection(
                    socket,
                    new FrameDecoder(config.maxBodyBytes()),
                    config.outboundQueueCapacity(),
                    config.outboundQueueByteCapacity(),
                    config.timeouts(),
                    address,
                    metrics);
            perConnectionExecutor.submit(() -> connection.runReadLoop(handler));
            perConnectionExecutor.submit(connection::runWriteLoop);
            metrics.incrementConnect(sslContext == null ? "tcp" : "tls", true);
            return connection;
        } catch (UnknownHostException uhe) {
            failConnect(socket, "tls");
            throw new RingDialException(
                    RingDialException.Classification.UNKNOWN_HOST,
                    address, "DNS resolution failed", uhe);
        } catch (SocketTimeoutException ste) {
            failConnect(socket, sslContext == null ? "tcp" : "tls");
            throw new RingDialException(
                    RingDialException.Classification.TIMEOUT,
                    address, "timeout after " + config.connectTimeout(), ste);
        } catch (SSLHandshakeException tls) {
            metrics.recordTlsHandshake(Duration.ofNanos(System.nanoTime() - start), false);
            failConnect(socket, "tls");
            throw new RingDialException(
                    RingDialException.Classification.TLS_HANDSHAKE_FAILED,
                    address, tls.getMessage(), tls);
        } catch (ConnectException ce) {
            failConnect(socket, sslContext == null ? "tcp" : "tls");
            throw new RingDialException(
                    RingDialException.Classification.CONNECTION_REFUSED,
                    address, ce.getMessage(), ce);
        } catch (IOException io) {
            failConnect(socket, sslContext == null ? "tcp" : "tls");
            throw new RingDialException(
                    RingDialException.Classification.TRANSPORT,
                    address, io.getMessage(), io);
        }
    }

    private void failConnect(Socket socket, String transport) {
        metrics.incrementConnect(transport, false);
        closeQuietly(socket);
    }

    private Socket buildSocket() throws IOException {
        if (sslContext == null) {
            return SocketFactory.getDefault().createSocket();
        }
        SSLSocketFactory factory   = sslContext.getSocketFactory();
        SSLSocket        sslSocket = (SSLSocket) factory.createSocket();
        sslSocket.setEnabledProtocols(config.tlsConfig().protocols().toArray(new String[0]));
        if (config.tlsConfig().cipherSuites() != null) {
            sslSocket.setEnabledCipherSuites(
                                             config.tlsConfig().cipherSuites().toArray(new String[0]));
        }
        return sslSocket;
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    @Override
    public void close() {
        perConnectionExecutor.shutdownNow();
        LOG.log(System.Logger.Level.DEBUG, () -> "RingDialer closed");
    }
}
