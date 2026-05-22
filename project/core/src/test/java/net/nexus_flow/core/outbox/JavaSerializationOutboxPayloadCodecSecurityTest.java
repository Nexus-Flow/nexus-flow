package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.regex.Pattern;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.DomainEvent;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link JavaSerializationOutboxPayloadCodec} deserialization-filter contract (CWE-502
 * mitigation).
 *
 * <p>Java standard deserialization is an industry-recognised RCE vector — a crafted byte stream can
 * trigger arbitrary code execution during {@code readObject} via gadget chains in libraries like
 * Apache Commons Collections, ROME, BeanShell. The framework defends with an {@link
 * ObjectInputFilter} that allowlists framework + JDK stdlib + the payload's package and rejects
 * everything else. This regression pins:
 *
 * <ul>
 * <li>Round-trip of a normal event (from the payload's package) succeeds.
 * <li>Round-trip of an event from an unrelated package is REJECTED — proves the package allowlist
 * is enforced and the codec is not silently widening the attack surface.
 * <li>Custom filter constructor REPLACES the default — operators can opt in to richer types if
 * they have audited the trust boundary.
 * <li>The DoS caps ({@code maxbytes} / {@code maxrefs} / {@code maxdepth} / {@code maxarray}) are
 * exposed as public constants for ops visibility.
 * </ul>
 *
 * <p>Note: there is no need to forge an actual gadget-chain payload to prove the filter works — the
 * JDK's {@link ObjectInputFilter} contract is well-established and tested by the JDK itself.
 * Asserting that the filter rejects a known-non-allowlisted class is sufficient.
 */
class JavaSerializationOutboxPayloadCodecSecurityTest {

    // Event living in the test package — covered by the default filter's payload-package allowlist.
    static final class LocalEvent extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        LocalEvent(String id) {
            super(id);
        }
    }

    /**
     * Serializable class living OUTSIDE any allowlisted package. The codec must REJECT this when
     * passed as raw bytes (after we hand-craft a stream that contains it).
     */
    static final class ForeignSerializable implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        final String              payload;

        ForeignSerializable(String payload) {
            this.payload = payload;
        }
    }

    @Test
    void roundTrip_eventFromAllowedPackage_succeeds() {
        JavaSerializationOutboxPayloadCodec codec    = new JavaSerializationOutboxPayloadCodec();
        LocalEvent                          original = new LocalEvent("agg-1");
        byte[]                              bytes    = codec.encode(original);
        DomainEvent                         decoded  = codec.decode(bytes, LocalEvent.class);
        assertNotNull(decoded);
        assertEquals(LocalEvent.class, decoded.getClass());
    }

    @Test
    void decode_streamContainingForeignClass_isRejectedByDefaultFilter() throws Exception {
        // Hand-craft a stream containing java.util.regex.Pattern — Serializable, JDK stdlib, but
        // NOT in any of the default-filter allowlist namespaces (java.util.* matches java.util.X but
        // not java.util.regex.X). Stands in for any non-framework, non-allowlisted serializable
        // class a malicious actor with outbox-write access might inject.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(Pattern.compile(".*"));
        }
        byte[] bytes = baos.toByteArray();

        JavaSerializationOutboxPayloadCodec codec = new JavaSerializationOutboxPayloadCodec();
        OutboxCodecException                ex    =
                assertThrows(
                             OutboxCodecException.class,
                             () -> codec.decode(bytes, LocalEvent.class),
                             "default filter MUST reject java.util.regex.Pattern (not in framework/payload/stdlib"
                                     + " allowlist)");
        Throwable                           cause = ex.getCause();
        assertNotNull(cause, "filter rejection must propagate a cause");
        assertTrue(
                   cause instanceof java.io.InvalidClassException || cause.getClass().getSimpleName().contains("Filter") || (cause
                           .getMessage() != null && cause.getMessage().toLowerCase(java.util.Locale.ROOT).contains("filter")),
                   "cause should signal a filter rejection; got " + cause);
    }

    @Test
    void customFilter_replacesDefault_allowingForeignClass() throws Exception {
        // An operator that has audited the trust boundary can pass a wider filter.
        ObjectInputFilter                   wider =
                ObjectInputFilter.Config.createFilter(
                                                      "java.lang.*;java.util.*;net.nexus_flow.core.**;"
                                                              + "net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodecSecurityTest$**;"
                                                              + "!*");
        JavaSerializationOutboxPayloadCodec codec = new JavaSerializationOutboxPayloadCodec(wider);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(new ForeignSerializable("now-allowed"));
        }
        byte[] bytes = baos.toByteArray();

        // The custom filter allows ForeignSerializable, so we get past the filter. The codec then
        // rejects it because it is not a DomainEvent — exactly the next defensive layer.
        OutboxCodecException ex =
                assertThrows(OutboxCodecException.class, () -> codec.decode(bytes, LocalEvent.class));
        assertTrue(
                   ex.getMessage().contains("not a DomainEvent"),
                   "after custom filter accepts the class, the codec must still reject non-events; got: "
                           + ex.getMessage());
    }

    @Test
    void customFilterConstructor_nullArgument_throwsNpe() {
        assertThrows(
                     NullPointerException.class,
                     () -> new JavaSerializationOutboxPayloadCodec((ObjectInputFilter) null));
    }

    @Test
    void dosCaps_areExposedAsConstants_forOperatorVisibility() {
        assertTrue(JavaSerializationOutboxPayloadCodec.DEFAULT_MAX_BYTES > 0);
        assertTrue(JavaSerializationOutboxPayloadCodec.DEFAULT_MAX_REFS > 0);
        assertTrue(JavaSerializationOutboxPayloadCodec.DEFAULT_MAX_DEPTH > 0);
        assertTrue(JavaSerializationOutboxPayloadCodec.DEFAULT_MAX_ARRAY > 0);
    }
}
