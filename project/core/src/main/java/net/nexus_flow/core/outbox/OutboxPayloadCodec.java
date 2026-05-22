package net.nexus_flow.core.outbox;

import net.nexus_flow.core.ddd.DomainEvent;

/**
 * Pluggable encoder/decoder used by the outbox path to persist the {@link DomainEvent} payload as
 * {@code byte[]} in {@link OutboxRecord#payloadBytes()} and to reconstruct it when the {@link
 * OutboxWorker} drains a row.
 *
 * <h2>SPI hardening</h2>
 *
 * Originally {@code sealed permits JavaSerializationOutboxPayloadCodec}; relaxed to a regular
 * interface so framework-integration modules (e.g. a future {@code nexus-flow-jdbc} JSON/Avro
 * codec, a {@code nexus-flow-kafka-dlq} Protobuf codec) can ship custom codecs without touching
 * {@code core}. The integrator owns serialization format choice (JSON, Avro, Protobuf, …) and any
 * version-evolution strategy on top of the {@code (bytes, payloadType)} contract.
 *
 * <h2>Round-trip contract</h2>
 *
 * For every event {@code e} for which the implementation succeeds, {@code decode(encode(e),
 * e.getClass()).idempotencyKey()} MUST equal {@code e.idempotencyKey()}. The codec itself does
 * <strong>not</strong> need to be reversible across different codec implementations — a given row
 * is bound to the codec that wrote it (see {@link #codecId()}).
 *
 * <h2>Multi-codec deployments and the codecId discriminator</h2>
 *
 * When a single runtime needs to read rows produced by more than one codec (typical during a
 * wire-format migration — old rows in Avro v1, new rows in Avro v2; or legacy Java-serialization
 * rows coexisting with new JSON rows during cutover), each row carries a {@link #codecId()} string.
 * A worker dispatcher uses that string to look up the matching codec from a registry and route the
 * decode call.
 *
 * <p>The framework's in-core {@link OutboxRecord} does not yet carry the {@code codecId} column —
 * adapter modules that need multi-codec routing add it to their persisted schema and resolve it via
 * a codec registry they own. {@code codecId()} is added to the SPI now so adapter authors have a
 * stable identity to persist; the default implementation returns the FQN of the codec class, which
 * is a reasonable default but adapter authors who plan to rename / repackage their codec across
 * versions SHOULD override with a stable string (e.g. {@code "json-v1"}).
 *
 * <h2>Schema evolution guidance for codec authors</h2>
 *
 * <ul>
 * <li><strong>Forwards compatibility</strong> (old reader, new writer): the codec MUST tolerate
 * unknown fields in the encoded bytes — strip them silently or log at {@code DEBUG}. Avro
 * achieves this via its schema-resolution rules; JSON codecs via {@code
 *       FAIL_ON_UNKNOWN_PROPERTIES=false}.
 * <li><strong>Backwards compatibility</strong> (new reader, old writer): every newly-added field
 * MUST have a default value the codec applies when the field is absent. {@link
 * net.nexus_flow.core.ddd.AbstractDomainEvent}'s own {@code readObject} uses {@code
 *       ObjectInputStream.GetField.get} with explicit defaults as the reference pattern.
 * <li><strong>Breaking changes</strong>: bump {@link #codecId()} (e.g. {@code "json-v1"} → {@code
 *       "json-v2"}). The worker's codec registry must contain both during the migration window;
 * rows written before the bump continue to decode through v1, new rows go through v2.
 * <li><strong>Sensitive payloads</strong>: do NOT include secrets, PII, or auth tokens in event
 * payloads. The outbox persists bytes at rest; whatever the codec encodes lands in the
 * backing store. For PII, encrypt at the codec layer (Avro + AWS KMS, JSON + Tink, …) and
 * audit the codec's key-management story.
 * </ul>
 *
 * <h2>Headers and observability ids on replay</h2>
 *
 * <p>{@link net.nexus_flow.core.ddd.AbstractDomainEvent#getHeaders()} is marked {@code transient}
 * and is NOT persisted to the outbox — the canonical trace / correlation / causation IDs travel via
 * {@link OutboxRecord#traceId()}, {@link OutboxRecord#correlationId()}, and {@link
 * OutboxRecord#causationId()} respectively, which the worker restores into the rebuilt {@link
 * net.nexus_flow.core.runtime.ExecutionContext} on replay. Tenant id is persisted via {@link
 * OutboxRecord#tenantId()}. Custom headers on the event itself are intentionally lost on replay;
 * consumers that need durable per-event metadata persist it as a typed field on the domain event
 * subclass (which the codec will encode along with the rest of the payload).
 */
public interface OutboxPayloadCodec {

    /**
     * Stable identity of this codec instance, persisted alongside every row encoded by it so a
     * multi-codec worker can route the decode call to the right codec implementation.
     *
     * <p>The default implementation returns the FQN of the implementing class — adequate for
     * single-codec deployments and for codecs that never rename or repackage. Authors who anticipate
     * either of those (rename, repackage, schema-version bump) should override and return a stable
     * external identifier such as {@code "json-v1"} or {@code "avro:order-events:v2"}.
     *
     * <p>The framework's in-core {@link OutboxRecord} does not yet carry a {@code codecId} column;
     * adapter modules that need multi-codec routing add the column to their persisted schema and
     * resolve it via a codec registry they own. See class Javadoc for the migration playbook.
     *
     * @return a stable, non-empty codec identity; never {@code null}
     */
    default String codecId() {
        return getClass().getName();
    }

    /**
     * Serialise {@code event} into the byte sequence that will be persisted in the outbox row.
     *
     * @throws OutboxCodecException if {@code event} cannot be encoded (e.g. not {@link
     *                              java.io.Serializable} when using the Java-serialization default).
     */
    byte[] encode(DomainEvent event);

    /**
     * Reverse of {@link #encode(DomainEvent)}. {@code payloadType} is the original event class
     * persisted alongside the bytes; the implementation may use it as a hint or ignore it.
     *
     * @throws OutboxCodecException if the bytes cannot be reconstructed (corrupt, wrong codec
     *                              version, class not on the classpath, …).
     */
    DomainEvent decode(byte[] bytes, Class<?> payloadType);
}
