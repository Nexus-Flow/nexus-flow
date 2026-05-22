package net.nexus_flow.core.ring.transport;

import java.io.IOException;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;

/**
 * SPI for sourcing — and rotating — the mTLS {@link SSLContext} that backs the ring acceptor and
 * dialer.
 *
 * <p>The default core implementation backed by {@link RingTlsConfig} reads keystores from disk at
 * construction time and does not rotate. Production deployments behind cert-manager / Vault / AWS
 * ACM / SPIFFE/SPIRE want hot rotation without dropping live connections; an adapter module wires a
 * {@code CertificateSource} that watches the cert source (a Kubernetes Secret, a file with inotify,
 * a Vault lease, an SPIFFE workload API stream) and calls {@link RotationListener#onRotation(SSLContext)}
 * whenever new credentials are available.
 *
 * <h2>Rotation semantics</h2>
 *
 * <p>Rotation is observable, not mandatory. After {@code onRotation} fires:
 *
 * <ul>
 * <li>Existing established TLS sessions remain valid until the peer closes the connection. The
 * acceptor and dialer use the NEW {@code SSLContext} for every subsequent handshake.
 * <li>The transport may opt to close existing connections gracefully (TLS close_notify) so the
 * peers re-handshake against the fresh credentials — at the cost of brief connection churn.
 * <li>The transport SHOULD log the rotation at INFO with a redacted fingerprint of the new
 * certificate (e.g. SHA-256 of the leaf cert) so operators can correlate with their cert-
 * manager rotation events.
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * Implementations invoke the listener on whatever thread observed the cert change — that is
 * typically a watcher / poller thread owned by the adapter. Listener callbacks MUST be fast: the
 * canonical pattern is "atomically swap the cached SSLContext reference and return". The transport
 * does NOT block the listener thread on a connection-rebuild.
 *
 * <h2>Lifecycle</h2>
 *
 * Implementations are {@link AutoCloseable}: {@link #close()} releases watcher resources (file
 * descriptors, Kubernetes watch streams, Vault renewal tasks) and stops invoking the listener.
 * The transport calls {@code close()} when the ring shuts down.
 */
public interface CertificateSource extends AutoCloseable {

    /**
     * Load the current {@link SSLContext}. Called once at transport boot — before any listener
     * callback can fire — and forms the initial security posture of the acceptor / dialer.
     *
     * @throws IOException              if the certificate material cannot be read (file
     *                                  missing, secret unavailable, network unreachable)
     * @throws GeneralSecurityException if the material is corrupt, mis-encoded, or signed by
     *                                  an untrusted authority
     */
    SSLContext load() throws IOException, GeneralSecurityException;

    /**
     * Subscribe to rotation events. The returned handle MUST be closed to unsubscribe; the
     * implementation guarantees no further callbacks after {@code close()} returns.
     *
     * <p>Default: a no-op subscription. Sources that never rotate (file-backed, no watcher) keep
     * the default; adapter modules with a real rotation channel override.
     */
    default Subscription subscribe(RotationListener listener) {
        return Subscription.NO_OP;
    }

    @Override
    void close();

    /**
     * Callback invoked when the cert source has observed a rotation. The listener receives the
     * brand-new {@link SSLContext} ready to use — the source has already loaded the material from
     * its backing store.
     */
    @FunctionalInterface
    interface RotationListener {
        void onRotation(SSLContext freshContext);
    }

    /** Subscription handle. */
    @FunctionalInterface
    interface Subscription extends AutoCloseable {
        @Override
        void close();

        /** No-op subscription returned by sources that never rotate. */
        Subscription NO_OP = () -> {
        };
    }

    /**
     * Wrap a static {@link RingTlsConfig} as a {@link CertificateSource} that never rotates. This
     * is the core default — adapter modules (k8s, Vault, SPIFFE) replace it with a real watcher.
     */
    static CertificateSource ofStaticConfig(RingTlsConfig config) {
        java.util.Objects.requireNonNull(config, "config");
        return new CertificateSource() {
            @Override
            public SSLContext load() throws IOException, GeneralSecurityException {
                return config.buildSslContext();
            }

            @Override
            public void close() {
                // nothing to release — keystores are read on demand
            }

            @Override
            public String toString() {
                return "CertificateSource.ofStaticConfig(" + config.keystorePath() + ")";
            }
        };
    }
}
