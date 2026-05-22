package net.nexus_flow.core.ring.transport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.jspecify.annotations.Nullable;

/**
 * mTLS configuration for the ring transport. Carries the keystore (this pod's identity) and
 * truststore (the certs of every peer the pod is willing to talk to) plus the protocol and
 * cipher policy.
 *
 * <p>Production deployments MUST supply a non-null {@code RingTlsConfig} to {@link
 * RingAcceptorConfig.Builder#tlsConfig(RingTlsConfig)}. The plain-TCP fallback path is
 * test-only and logs a {@code WARNING} at acceptor start so it is visible in any production
 * misconfiguration.
 *
 * <h2>Why mTLS over server-only TLS</h2>
 *
 * Every peer in the ring is both a client and a server (dial out, accept in). Mutual
 * authentication means every connection cryptographically pins BOTH ends — a compromised
 * intermediate peer cannot impersonate a legitimate one even if it knows the wire protocol.
 * Per skill §15: hostname verification ON, no SSLEngine (we use blocking SSLSocket since the
 * transport is blocking I/O + virtual threads, not a selector loop).
 *
 * <p>Lifecycle: this record builds an {@link SSLContext} via {@link #buildSslContext()} on
 * each call. Callers cache it for the lifetime of the acceptor / dialer; recreating an
 * {@code SSLContext} per connection would defeat session caching and add measurable handshake
 * cost. The class is immutable and re-readable; rotating keys means building a new {@code
 * RingTlsConfig} and a new acceptor.
 *
 * @param keystorePath       path to a PKCS12 / JKS keystore containing this pod's private key + cert
 *                           chain; must not be {@code null}
 * @param keystorePassword   password for the keystore; never {@code null} (use empty array, not
 *                           null, for unencrypted stores)
 * @param truststorePath     path to a PKCS12 / JKS truststore containing the certificates of every
 *                           peer this pod will accept; must not be {@code null}
 * @param truststorePassword password for the truststore; never {@code null}
 * @param protocols          list of enabled TLS protocols; defaults to {@link #DEFAULT_PROTOCOLS} (TLS
 *                           1.3 only — TLS 1.2 is also acceptable on JDK 25, but a 1.3-only posture is the
 *                           stricter default and works against every modern JDK
 * @param cipherSuites       optional explicit cipher suite list; {@code null} accepts the JDK
 *                           default (which for TLS 1.3 is already a small, well-vetted set)
 * @param keystoreType       keystore type label (PKCS12 / JKS); defaults to {@code "PKCS12"}
 * @param truststoreType     truststore type label; defaults to {@code "PKCS12"}
 */
public record RingTlsConfig(
                            Path keystorePath,
                            char[] keystorePassword,
                            Path truststorePath,
                            char[] truststorePassword,
                            List<String> protocols,
                            @Nullable List<String> cipherSuites,
                            String keystoreType,
                            String truststoreType) {

    /** TLS 1.3 only. JDK 25 supports it by default; older clients are refused. */
    public static final List<String> DEFAULT_PROTOCOLS = List.of("TLSv1.3");

    /** Default keystore type — PKCS12 is portable and supported on every modern JDK. */
    public static final String DEFAULT_STORE_TYPE = "PKCS12";

    public RingTlsConfig {
        Objects.requireNonNull(keystorePath, "keystorePath");
        Objects.requireNonNull(keystorePassword, "keystorePassword");
        Objects.requireNonNull(truststorePath, "truststorePath");
        Objects.requireNonNull(truststorePassword, "truststorePassword");
        Objects.requireNonNull(protocols, "protocols");
        Objects.requireNonNull(keystoreType, "keystoreType");
        Objects.requireNonNull(truststoreType, "truststoreType");
        if (protocols.isEmpty()) {
            throw new IllegalArgumentException("protocols must not be empty");
        }
        protocols    = List.copyOf(protocols);
        cipherSuites = (cipherSuites == null) ? null : List.copyOf(cipherSuites);
    }

    /**
     * Convenience builder for the common case: PKCS12 stores, TLS 1.3 only, JDK-default cipher
     * suites.
     */
    public static RingTlsConfig pkcs12(
            Path keystorePath,
            char[] keystorePassword,
            Path truststorePath,
            char[] truststorePassword) {
        return new RingTlsConfig(
                keystorePath,
                keystorePassword,
                truststorePath,
                truststorePassword,
                DEFAULT_PROTOCOLS,
                null,
                DEFAULT_STORE_TYPE,
                DEFAULT_STORE_TYPE);
    }

    /**
     * Build an {@link SSLContext} configured for mutual authentication. Callers cache the
     * result for the lifetime of the acceptor / dialer.
     *
     * @return a fully initialised {@link SSLContext}
     * @throws IOException              if either store cannot be read
     * @throws GeneralSecurityException if the store is corrupt, the password is wrong, or the
     *                                  algorithm is unsupported
     */
    public SSLContext buildSslContext() throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(keystoreType);
        try (InputStream in = Files.newInputStream(keystorePath)) {
            ks.load(in, keystorePassword);
        }
        KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keystorePassword);

        KeyStore ts = KeyStore.getInstance(truststoreType);
        try (InputStream in = Files.newInputStream(truststorePath)) {
            ts.load(in, truststorePassword);
        }
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);

        // Use the generic "TLS" algorithm so the SSLContext supports the full set of TLS
        // versions; the protocol restriction is applied at the SSLSocket layer via
        // setEnabledProtocols(...). Passing the version name to getInstance(...) (e.g.
        // "TLSv1.3") returns a version-locked context whose interaction with
        // setEnabledProtocols is provider-dependent — a trap noted in the audit.
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }
}
