package net.nexus_flow.core.ring.dispatch;

/**
 * Pluggable encoder/decoder for command payload records dispatched across the ring. The
 * symmetric counterpart of {@link net.nexus_flow.core.outbox.OutboxPayloadCodec} for the
 * command path — the outbox codec is event-only and cannot be reused for commands because
 * commands are arbitrary {@code Record}s, not {@link net.nexus_flow.core.ddd.DomainEvent}s.
 *
 * <h2>Why a dedicated SPI</h2>
 *
 * Cross-pod commands need the SAME guarantees the outbox does (multi-format support,
 * versioning, no Java-serialization-by-default for untrusted peers) but with a payload
 * type that is unrelated to {@code DomainEvent}. A dedicated SPI lets adapter modules
 * ship a JSON / Avro / Protobuf command codec independently of the event codec and keeps
 * the wire-format choice for commands separate from the choice for events — production
 * deployments routinely use different formats for different concerns.
 *
 * <h2>Codec id discriminator</h2>
 *
 * The codec's {@link #codecId()} is sent on the wire in
 * {@link DispatchRequestEnvelope#codecId()}. The receiver consults its own codec registry
 * keyed on that id and decodes via the matching codec. This is the same multi-codec
 * routing model the outbox uses; the framework intentionally keeps the two registries
 * separate so adapter modules can introduce a new command codec without disturbing the
 * event codec set.
 *
 * <h2>Round-trip contract</h2>
 *
 * For every record {@code r} the implementation can encode, {@code decode(encode(r),
 * r.getClass())} MUST return an object equal to {@code r} (records compare by value).
 *
 * <h2>Implementations</h2>
 *
 * The framework ships only an SPI — concrete codecs live in adapter modules
 * ({@code nexus-flow-jackson-command-codec}, {@code nexus-flow-avro-command-codec},
 * etc.). Tests use a simple identity codec or Java-serialization codec.
 */
public interface CommandPayloadCodec {

    /**
     * Encode {@code body} to bytes for cross-pod dispatch.
     *
     * @param body the command record; must not be {@code null}
     * @return the encoded bytes; never {@code null}
     */
    byte[] encode(Record body);

    /**
     * Decode {@code bytes} into an instance of {@code recordType}.
     *
     * @param bytes      the wire bytes
     * @param recordType the expected record class; must not be {@code null}
     * @param <T>        the record type
     * @return the decoded record
     */
    <T extends Record> T decode(byte[] bytes, Class<T> recordType);

    /**
     * Stable string identifier the receiver uses to look this codec up in its registry.
     * Default implementation returns the codec's FQN — fine for single-codec deployments;
     * adapter authors who plan to rename / repackage SHOULD override with a stable string
     * (e.g. {@code "json-v1"}, {@code "avro-v2"}).
     */
    default String codecId() {
        return getClass().getName();
    }
}
