package net.nexus_flow.core.ring.dispatch;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Default {@link CommandPayloadCodec} backed by Java standard serialization. Mirrors the
 * security model of
 * {@link net.nexus_flow.core.outbox.JavaSerializationOutboxPayloadCodec}: every
 * {@link #decode(byte[], Class)} installs an {@link ObjectInputFilter} with an allowlist
 * for the JDK stdlib + the framework's own packages + the payload's package, plus DoS
 * caps on stream size, reference count, depth, and array length.
 *
 * <h2>When NOT to use this codec</h2>
 *
 * Production deployments that cross trust boundaries SHOULD prefer a JSON / Avro /
 * Protobuf codec from an adapter module — Java serialization is a fragile wire format
 * (no schema evolution, brittle to refactors, JVM-only) and the allowlist is a
 * defense-in-depth measure, not a substitute for an explicit schema. This codec exists
 * so the framework's tests and single-language single-cluster deployments can exercise
 * the {@link RingCommandFallback} cross-pod path without dragging in a third-party
 * serializer.
 *
 * <h2>Records and Serializable</h2>
 *
 * Java records implement {@link java.io.Serializable} transparently as long as every
 * component type is itself Serializable. The framework's {@code Command<T extends Record>}
 * signature ensures the payload IS a record; the codec relies on the runtime check.
 *
 * <h2>Codec id</h2>
 *
 * Stable codec identity {@code "java-cmd-v1"}, persisted on the wire in
 * {@link DispatchRequestEnvelope#codecId()}. Adapters that ship a Java-serialization
 * variant with different filter rules MUST use a different codec id (e.g. {@code
 * "java-cmd-v2"}) so both can coexist during migration.
 */
public final class JavaSerializationCommandPayloadCodec implements CommandPayloadCodec {

    /** Stable codec identity persisted on the wire. */
    public static final String CODEC_ID = "java-cmd-v1";

    /** Maximum encoded payload size accepted by the default filter (10 MiB). */
    public static final int DEFAULT_MAX_BYTES = 10 * 1024 * 1024;

    /** Maximum number of object references accepted by the default filter. */
    public static final int DEFAULT_MAX_REFS = 10_000;

    /** Maximum object-graph depth accepted by the default filter. */
    public static final int DEFAULT_MAX_DEPTH = 20;

    /** Maximum array length accepted by the default filter. */
    public static final int DEFAULT_MAX_ARRAY = 10_000;

    private static final String[] DEFAULT_ALLOWED_PATTERNS =
            {"java.lang.*", "java.lang.Enum", "java.lang.Number", "java.lang.Throwable", "java.lang.StackTraceElement", "java.lang.reflect.Method", "java.util.*", "java.util.concurrent.atomic.*", "java.time.*", "java.time.chrono.*", "java.math.*", "java.net.URI", "java.net.URL", "java.io.Serializable", "java.io.Externalizable", "[B", "[Z", "[I", "[J", "[F", "[D", "[S", "[C", "net.nexus_flow.core.**"
            };

    private final @Nullable ObjectInputFilter customFilter;

    /** Default constructor — builds the per-call filter dynamically. */
    public JavaSerializationCommandPayloadCodec() {
        this.customFilter = null;
    }

    /**
     * Construct a codec that installs the supplied {@link ObjectInputFilter} verbatim on
     * every {@link #decode(byte[], Class)} — bypassing the per-call default. Use when the
     * deployment needs a tighter or looser allowlist than the framework default.
     */
    public JavaSerializationCommandPayloadCodec(ObjectInputFilter filter) {
        this.customFilter = Objects.requireNonNull(filter, "filter");
    }

    @Override
    public byte[] encode(Record body) {
        Objects.requireNonNull(body, "body");
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(body);
            oos.flush();
            return baos.toByteArray();
        } catch (IOException ioe) {
            throw new UncheckedIOException("failed to encode " + body.getClass().getName(), ioe);
        }
    }

    @Override
    public <T extends Record> T decode(byte[] bytes, Class<T> recordType) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(recordType, "recordType");
        ObjectInputFilter effective =
                customFilter == null ? buildDefaultFilterFor(recordType) : customFilter;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                ObjectInputStream ois = new ObjectInputStream(bais)) {
            ois.setObjectInputFilter(effective);
            Object obj = ois.readObject();
            if (!recordType.isInstance(obj)) {
                throw new IllegalArgumentException(
                        "decoded object " + obj.getClass().getName()
                                + " is not assignable to " + recordType.getName());
            }
            return recordType.cast(obj);
        } catch (IOException ioe) {
            throw new UncheckedIOException(
                    "failed to decode bytes into " + recordType.getName(), ioe);
        } catch (ClassNotFoundException cnf) {
            throw new IllegalArgumentException(
                    "class not found while decoding into " + recordType.getName(), cnf);
        }
    }

    @Override
    public String codecId() {
        return CODEC_ID;
    }

    private static ObjectInputFilter buildDefaultFilterFor(Class<?> payloadType) {
        StringBuilder pattern = new StringBuilder(512)
                .append("maxbytes=").append(DEFAULT_MAX_BYTES)
                .append(";maxrefs=").append(DEFAULT_MAX_REFS)
                .append(";maxdepth=").append(DEFAULT_MAX_DEPTH)
                .append(";maxarray=").append(DEFAULT_MAX_ARRAY)
                .append(';');
        for (String allowed : DEFAULT_ALLOWED_PATTERNS) {
            pattern.append(allowed).append(';');
        }
        String payloadPackage = payloadType.getPackageName();
        if (!payloadPackage.isEmpty()) {
            pattern.append(payloadPackage).append(".*;")
                    .append(payloadPackage).append(".**;");
        }
        pattern.append("!*");
        return ObjectInputFilter.Config.createFilter(pattern.toString());
    }
}
