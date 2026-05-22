package net.nexus_flow.core.outbox;

import java.util.Optional;

/**
 * Adapter-facing SPI for resolving an {@link OutboxPayloadCodec} from the {@link
 * OutboxRecord#codecId()} string persisted alongside the row.
 *
 * <h2>When this matters</h2>
 *
 * Single-codec deployments (the common case) do NOT need a registry — the {@link OutboxWorker}
 * reads {@link OutboxConfig#codec()} for every row and ignores {@link OutboxRecord#codecId()}. The
 * registry only matters when more than one codec must coexist:
 *
 * <ul>
 * <li><strong>Schema migration:</strong> rows produced before a wire-format change still need to
 * decode through the old codec, while new rows use the new one. Each row carries the {@code
 *       codecId} that wrote it; the registry routes the decode call to the matching codec
 * implementation.
 * <li><strong>Multi-format outbox:</strong> a single outbox may carry rows in different formats
 * (e.g. a legacy Java-serialization tail + a forward-cut JSON head) when an adapter chooses
 * to support several encodings per deployment.
 * <li><strong>Polyglot consumers:</strong> integration tests or canary deployments that flip
 * codecs per row to validate consumer compatibility without rewriting the corpus.
 * </ul>
 *
 * <h2>Routing precedence (consulted in order)</h2>
 *
 * <ol>
 * <li>If {@link OutboxConfig#codecRegistry()} is non-null AND {@link OutboxRecord#codecId()} is
 * non-null AND the registry returns a codec for that id, the worker uses that codec.
 * <li>Otherwise the worker falls back to {@link OutboxConfig#codec()} — the single primary codec.
 * Legacy rows (written before the {@code codecId} field existed) carry {@code codecId ==
 *       null} and always take this path; this preserves backwards-compat for any persisted row
 * written by a prior version of the framework.
 * </ol>
 *
 * <h2>Thread safety</h2>
 *
 * Implementations MUST be safe for concurrent {@link #get(String)} calls — every record the worker
 * drains lands here from the worker thread, and tooling may consult the registry concurrently. Most
 * implementations will be backed by an immutable map, populated once at runtime construction time.
 *
 * <h2>Failure semantics</h2>
 *
 * Returning {@link Optional#empty()} when the {@code codecId} is unknown is a CORRECTNESS signal,
 * not a "fallback to primary" signal — the worker treats it as a decode failure and sends the row
 * through {@link OutboxWorker}'s terminal-failure path (the row goes to {@link
 * OutboxStatus#FAILED_TERMINAL} and a manual replay is required after the missing codec is added to
 * the registry). This is intentional: silently routing a stranded row through the wrong codec would
 * deserialize garbage and is the worst kind of production failure (success shaped + garbage
 * payload).
 */
@FunctionalInterface
public interface OutboxPayloadCodecRegistry {

    /**
     * Returns the codec registered under {@code codecId}, or {@link Optional#empty()} if no such
     * codec is known.
     *
     * @param codecId stable codec identity persisted on the row; never {@code null}
     * @return the matching codec, or {@link Optional#empty()} if no codec is registered for this id
     */
    Optional<OutboxPayloadCodec> get(String codecId);
}
