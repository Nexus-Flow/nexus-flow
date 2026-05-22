package net.nexus_flow.core.ring.transport;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

/** Pins the {@link CertificateSource} SPI defaults. */
class CertificateSourceTest {

    @Test
    void noOpSubscription_closeIsIdempotent() {
        CertificateSource.Subscription sub = CertificateSource.Subscription.NO_OP;
        sub.close();
        sub.close();
        assertNotNull(sub, "sentinel must remain referenceable after close()");
    }

    @Test
    void source_withoutRotationOverride_returnsNoOpSubscription() {
        CertificateSource              source = new CertificateSource() {
                                                  @Override
                                                  public SSLContext load() {
                                                      return null;
                                                  }

                                                  @Override
                                                  public void close() {
                                                      // nothing
                                                  }
                                              };
        CertificateSource.Subscription sub    = source.subscribe(ctx -> {
                                              });
        assertSame(CertificateSource.Subscription.NO_OP, sub,
                   "sources that do not override subscribe MUST return the NO_OP sentinel");
        sub.close();
    }

    @Test
    void ofStaticConfig_toString_includesKeystorePath_forOperatorTrace() {
        RingTlsConfig     cfg    = new RingTlsConfig(
                java.nio.file.Paths.get("/tmp/ks.p12"),
                new char[]{'p'},
                java.nio.file.Paths.get("/tmp/ts.p12"),
                new char[]{'p'},
                RingTlsConfig.DEFAULT_PROTOCOLS,
                null,
                RingTlsConfig.DEFAULT_STORE_TYPE,
                RingTlsConfig.DEFAULT_STORE_TYPE);
        CertificateSource source = CertificateSource.ofStaticConfig(cfg);
        assertTrue(source.toString().contains("ks.p12"),
                   "toString must surface the keystore path so operators can correlate logs");
        source.close();
    }
}
