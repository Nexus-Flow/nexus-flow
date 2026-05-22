package net.nexus_flow.core.outbox;

import java.io.*;
import java.util.Objects;
import net.nexus_flow.core.ddd.DomainEvent;
import org.jspecify.annotations.Nullable;

/**
 * Default {@link OutboxPayloadCodec} backed by Java standard serialization.
 *
 * <p>Suitable for tests and JVM-local single-node deployments. Production deployments that need
 * cross-process or polyglot consumers SHOULD plug in a JSON / Avro / Protobuf alternative — see the
 * adapter modules ({@code nexus-flow-jdbc}, {@code nexus-flow-kafka}) for richer codecs.
 *
 * <h2>Security model — deserialization filter (CWE-502)</h2>
 *
 * <p>Java standard deserialization is an industry-recognised RCE vector: a crafted byte stream can
 * trigger arbitrary code execution during {@code readObject()} via "gadget chains" in libraries
 * like Apache Commons Collections, ROME, BeanShell, etc. The framework itself does NOT pull any of
 * those libraries in, but transitive dependencies in adapter modules or host applications may. To
 * defend against this, every {@link #decode(byte[], Class)} call installs an {@link
 * ObjectInputFilter} that:
 *
 * <ul>
 * <li>Allowlists JDK stdlib namespaces commonly used by domain events ({@code java.lang.*},
 * {@code java.util.*}, {@code java.time.*}, {@code java.math.*}, {@code java.net.*}, {@code
 *       java.io.*}, {@code java.util.concurrent.atomic.*}).
 * <li>Allowlists the framework's own packages ({@code net.nexus_flow.core.**}).
 * <li>Allowlists the package hierarchy of the {@code payloadType} hint passed by the worker
 * (under the assumption that the user's event types live in the same or child package).
 * <li>Rejects everything else.
 * <li>Caps the stream size ({@link #DEFAULT_MAX_BYTES}), reference count ({@link
 * #DEFAULT_MAX_REFS}), depth ({@link #DEFAULT_MAX_DEPTH}), and array size ({@link
 * #DEFAULT_MAX_ARRAY}) to bound denial-of-service vectors.
 * </ul>
 *
 * <p>Hosts that need richer types (custom enums, third-party value objects outside the payload's
 * package, etc.) pass a custom {@link ObjectInputFilter} to the {@link
 * #JavaSerializationOutboxPayloadCodec(ObjectInputFilter)} constructor; the supplied filter
 * REPLACES the per-call default. Use the JDK's {@code
 * ObjectInputFilter.Config.createFilter("pattern")} factory to assemble one.
 *
 * <p>Even with the filter, Java serialization is a fragile wire format (no version evolution story,
 * brittle to refactors, JVM-only). For production deployments prefer a JSON / Avro / Protobuf
 * adapter that has explicit schemas.
 *
 * <p>The codec requires every event reaching the outbox to implement {@link Serializable}. {@link
 * net.nexus_flow.core.ddd.AbstractDomainEvent} already does; user-defined events that extend it
 * inherit the marker automatically.
 */
public final class JavaSerializationOutboxPayloadCodec implements OutboxPayloadCodec {

    /**
     * Stable codec identity persisted by adapter modules that route rows to the matching codec on
     * decode. The string is intentionally short and version-tagged so a future Java-serialization
     * codec variant (with a different filter or different DoS caps) can ship as {@code "java-v2"} and
     * coexist with this one during migration.
     */
    public static final String CODEC_ID = "java-v1";

    /** Maximum encoded payload size accepted by the default filter. 10 MiB. */
    public static final int DEFAULT_MAX_BYTES = 10 * 1024 * 1024;

    /** Maximum number of object references accepted by the default filter. */
    public static final int DEFAULT_MAX_REFS = 10_000;

    /** Maximum object-graph depth accepted by the default filter. */
    public static final int DEFAULT_MAX_DEPTH = 20;

    /** Maximum array length accepted by the default filter. */
    public static final int DEFAULT_MAX_ARRAY = 10_000;

    /**
     * Allowlisted namespaces baked into the per-call default filter. Compiled with the per-call
     * {@code payloadType} package and the DoS caps to produce the effective {@link
     * ObjectInputFilter}.
     */
    private static final String[] DEFAULT_ALLOWED_PATTERNS =
            {"java.lang.*", "java.lang.Enum", "java.lang.Number", "java.lang.Throwable", "java.lang.StackTraceElement", "java.lang.reflect.Method", "java.util.*", "java.util.concurrent.atomic.*", "java.time.*", "java.time.chrono.*", "java.math.*", "java.net.URI", "java.net.URL", "java.io.Serializable", "java.io.Externalizable", "[B", // byte[]
                    "[Z", // boolean[]
                    "[I", // int[]
                    "[J", // long[]
                    "[D", // double[]
                    "[F", // float[]
                    "[Ljava.lang.String;", "[Ljava.lang.Object;", "net.nexus_flow.core.**"
            };

    private final @Nullable ObjectInputFilter customFilter;

    /**
     * Construct a codec that builds a per-call {@link ObjectInputFilter} (the documented default
     * allowlist plus the payload's package) on each {@link #decode(byte[], Class)} invocation.
     */
    public JavaSerializationOutboxPayloadCodec() {
        this.customFilter = null;
    }

    /**
     * Construct a codec that installs the supplied {@link ObjectInputFilter} verbatim on every
     * decode. The supplied filter REPLACES the per-call default — adapters that need richer types
     * should typically build their filter on top of the {@link #DEFAULT_ALLOWED_PATTERNS}-equivalent
     * set.
     *
     * @param filter the filter to install; must not be {@code null}
     */
    public JavaSerializationOutboxPayloadCodec(ObjectInputFilter filter) {
        this.customFilter = Objects.requireNonNull(filter, "filter");
    }

    @Override
    public String codecId() {
        return CODEC_ID;
    }

    /**
     * Serialises {@code event} using Java object serialization.
     *
     * @param event the domain event to encode; must implement {@link Serializable}
     * @return the serialised byte array; never {@code null}
     * @throws OutboxCodecException if {@code event} does not implement {@link Serializable} or if
     *                              serialization fails for any other reason
     */
    @Override
    public byte[] encode(DomainEvent event) {
        if (!(event instanceof Serializable)) {
            throw new OutboxCodecException(
                    "event "
                            + event.getClass().getName()
                            + " is not Serializable; either mark it Serializable"
                            + " or plug a non-Java OutboxPayloadCodec");
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(event);
            oos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new OutboxCodecException("failed to encode event " + event.getClass().getName(), e);
        }
    }

    /**
     * Deserialises a domain event from {@code bytes} using Java object deserialization, GATED by a
     * resolved {@link ObjectInputFilter} that allowlists framework + payload-package classes and caps
     * DoS-relevant limits.
     *
     * @param bytes       the serialised event bytes produced by {@link #encode}
     * @param payloadType the expected event class; drives the per-call filter's package allowlist and
     *                    surfaces in the error message if the decoded object is not a {@link DomainEvent}
     * @return the reconstructed {@link DomainEvent}; never {@code null}
     * @throws OutboxCodecException if the bytes are corrupt, the class is missing from the classpath,
     *                              the deserialization filter rejects the class, or the decoded object is not a {@link
     *                              DomainEvent}
     */
    @Override
    public DomainEvent decode(byte[] bytes, Class<?> payloadType) {
        ObjectInputFilter effective =
                customFilter != null ? customFilter : buildDefaultFilterFor(payloadType);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                ObjectInputStream ois = new ObjectInputStream(bais)) {
            ois.setObjectInputFilter(effective);
            Object o = ois.readObject();
            if (!(o instanceof DomainEvent ev)) {
                throw new OutboxCodecException(
                        "decoded object is not a DomainEvent: "
                                + (o == null ? "null" : o.getClass().getName())
                                + " (expected "
                                + payloadType.getName()
                                + ")");
            }
            return ev;
        } catch (IOException | ClassNotFoundException e) {
            throw new OutboxCodecException("failed to decode event " + payloadType.getName(), e);
        }
    }

    /**
     * Build the per-call default filter: framework + payload-package + JDK stdlib allowlist with the
     * documented DoS caps and a terminal {@code !*} reject rule.
     */
    private static ObjectInputFilter buildDefaultFilterFor(Class<?> payloadType) {
        String        payloadPackage = payloadType.getPackageName();
        StringBuilder pattern        = new StringBuilder(512);
        pattern
                .append("maxbytes=")
                .append(DEFAULT_MAX_BYTES)
                .append(";maxrefs=")
                .append(DEFAULT_MAX_REFS)
                .append(";maxdepth=")
                .append(DEFAULT_MAX_DEPTH)
                .append(";maxarray=")
                .append(DEFAULT_MAX_ARRAY)
                .append(';');
        for (String p : DEFAULT_ALLOWED_PATTERNS) {
            pattern.append(p).append(';');
        }
        // Allow the payload's own package and subpackages.
        if (!payloadPackage.isEmpty()) {
            pattern.append(payloadPackage).append(".**;");
        }
        // Reject everything else.
        pattern.append("!*");
        return ObjectInputFilter.Config.createFilter(pattern.toString());
    }
}
