package net.nexus_flow.core.ring.transport;

import java.security.cert.X509Certificate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The authenticated identity behind a {@link RingConnection}. Populated by the transport when
 * TLS is enabled and the handshake exposes a peer certificate via
 * {@link javax.net.ssl.SSLSession#getPeerCertificates()}; populated as
 * {@link #anonymous(PeerAddress)} for plain-TCP connections so authorization sinks can branch
 * on the principal type without a null check.
 *
 * <p>The principal is the input the {@code DispatchAuthorizer} consults — not the
 * self-asserted {@code peerId} from {@link
 * net.nexus_flow.core.ring.wire.HelloPayload#peerId()}. mTLS authenticates the certificate;
 * the certificate authenticates the principal; the principal authorizes the operation.
 *
 * @param subjectDistinguishedName the certificate's subject DN (RFC 2253 form), or
 *                                 {@code "anonymous"} for plain-TCP
 * @param peerCertificate          the verified peer certificate, or {@code null} for plain-TCP
 * @param remoteAddress            the remote network address (best-effort)
 * @param authenticated            {@code true} iff the principal was authenticated by TLS;
 *                                 {@code false} for the plain-TCP test/dev path
 */
public record RingTransportPrincipal(
                                     String subjectDistinguishedName,
                                     @Nullable X509Certificate peerCertificate,
                                     PeerAddress remoteAddress,
                                     boolean authenticated) {

    public RingTransportPrincipal {
        Objects.requireNonNull(subjectDistinguishedName, "subjectDistinguishedName");
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        if (authenticated && peerCertificate == null) {
            throw new IllegalArgumentException(
                    "authenticated principal MUST carry a peer certificate");
        }
    }

    /** Anonymous (plain-TCP, no mTLS) principal — only safe for test/dev deployments. */
    public static RingTransportPrincipal anonymous(PeerAddress remote) {
        return new RingTransportPrincipal("anonymous", null, remote, false);
    }

    /** Authenticated principal extracted from the verified peer certificate. */
    public static RingTransportPrincipal authenticated(
            X509Certificate cert, PeerAddress remote) {
        Objects.requireNonNull(cert, "cert");
        String dn = cert.getSubjectX500Principal().getName();
        return new RingTransportPrincipal(dn, cert, remote, true);
    }
}
