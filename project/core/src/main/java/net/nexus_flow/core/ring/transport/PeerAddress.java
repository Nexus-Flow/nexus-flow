package net.nexus_flow.core.ring.transport;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * The {@code (host, port)} network address of a peer. Distinct from {@link PeerId} (which is a
 * stable logical handle) because a peer's network address can change across restarts (new pod
 * IP) while its id stays the same.
 *
 * <p>Validation is intentionally permissive at construction — any non-blank host and a
 * port in [1, 65535]. DNS resolution and reachability are NOT verified here; that happens at
 * dial time and surfaces as an explicit {@link java.net.UnknownHostException} /
 * {@link java.net.ConnectException} classification (per skill §16, §7).
 *
 * @param host the hostname or IP address; never {@code null}, never blank
 * @param port the TCP port; must be in {@code [1, 65535]}
 */
public record PeerAddress(String host, int port) {

    public PeerAddress {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range [1, 65535]: " + port);
        }
    }

    /**
     * Canonical IPv4 loopback literal. Exposed as a constant so call sites do not repeat the
     * string and so PMD's {@code AvoidUsingHardCodedIP} is suppressed in ONE place — the rule
     * targets production-IP hardcoding (e.g. {@code "10.20.30.40"} as a server address); the
     * loopback address used by test fixtures and the sidecar factory is the intended,
     * unambiguous semantic of "this host".
     */
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    public static final String LOOPBACK_HOST = "127.0.0.1";

    /** Convenience factory for loopback addresses commonly used in tests and sidecar setups. */
    public static PeerAddress loopback(int port) {
        return new PeerAddress(LOOPBACK_HOST, port);
    }

    /**
     * Returns an {@link InetSocketAddress} for use with {@code Socket} / {@code ServerSocket}
     * APIs. Uses {@code createUnresolved} when the host looks like an IP literal to avoid an
     * unnecessary DNS lookup; otherwise lets the OS resolve at connect time.
     */
    public InetSocketAddress toInetSocketAddress() {
        return new InetSocketAddress(host, port);
    }

    @Override
    public String toString() {
        return host + ':' + port;
    }
}
