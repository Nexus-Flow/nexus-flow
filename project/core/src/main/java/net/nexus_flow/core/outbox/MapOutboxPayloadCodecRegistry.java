package net.nexus_flow.core.outbox;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory {@link OutboxPayloadCodecRegistry} backed by a fixed map. Populated once at runtime
 * construction time; reads are lock-free via the immutable map snapshot.
 *
 * <p>Adapter modules typically build one of these from a configuration list (e.g. {@code codec.0 =
 * json-v1; codec.1 = avro-v3}) and pass it to {@link OutboxConfig.Builder}. Hosts that need dynamic
 * re-registration (rare — typically only during canary rollouts) can wrap the registry behind a
 * volatile reference they swap atomically.
 *
 * <h2>Self-consistency check</h2>
 *
 * Every entry's map key MUST equal the corresponding codec's {@link OutboxPayloadCodec#codecId()}.
 * The compact constructor rejects mismatches eagerly — a key mismatch usually signals that the
 * operator typed the codecId in two places and one drifted, which would later surface as a silent
 * decode-routing bug. Failing at construction makes the drift visible immediately.
 */
public record MapOutboxPayloadCodecRegistry(Map<String, OutboxPayloadCodec> codecs)
        implements OutboxPayloadCodecRegistry {

    /**
     * Validates the map and freezes it via {@link Map#copyOf}.
     *
     * @throws IllegalArgumentException if any entry's key does not match the codec's {@link
     *                                  OutboxPayloadCodec#codecId()}
     * @throws NullPointerException     if {@code codecs} or any entry is {@code null}
     */
    public MapOutboxPayloadCodecRegistry {
        Objects.requireNonNull(codecs, "codecs");
        for (Map.Entry<String, OutboxPayloadCodec> e : codecs.entrySet()) {
            String             key   = Objects.requireNonNull(e.getKey(), "codec key");
            OutboxPayloadCodec value = Objects.requireNonNull(e.getValue(), "codec value for key " + key);
            if (!key.equals(value.codecId())) {
                throw new IllegalArgumentException(
                        "registry key '"
                                + key
                                + "' does not match codec.codecId()='"
                                + value.codecId()
                                + "' (codec class "
                                + value.getClass().getName()
                                + ")");
            }
        }
        codecs = Map.copyOf(codecs);
    }

    @Override
    public Optional<OutboxPayloadCodec> get(String codecId) {
        Objects.requireNonNull(codecId, "codecId");
        return Optional.ofNullable(codecs.get(codecId));
    }
}
