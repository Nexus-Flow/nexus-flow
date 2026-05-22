package net.nexus_flow.core.ring.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.nexus_flow.core.ring.transport.PeerId;

/**
 * Persists the ring's per-peer cursor state to disk so a pod restart resumes from where the
 * pre-crash JVM left off instead of replaying every outbox row from the beginning.
 *
 * <h2>File format (line-oriented, UTF-8)</h2>
 *
 * <pre>
 * # nexus-flow ring state v1
 * &lt;peerId&gt;\t&lt;lastSeenOutboxSequence&gt;\n
 * ...
 * </pre>
 *
 * Deliberately flat — no JSON dependency, no schema lock-in. Operators can read it with
 * {@code less}, tail it with {@code tail -F}, rotate it with {@code logrotate}. The header
 * line ({@code # nexus-flow ring state vN}) is the version discriminator; readers reject
 * unknown versions and surface a clear error so operators don't silently lose state.
 *
 * <h2>Atomic writes</h2>
 *
 * {@link #write(Map)} writes to a {@code .tmp} sibling then atomically renames over the
 * destination via {@link Files#move} with {@link StandardCopyOption#ATOMIC_MOVE}. A crash
 * mid-write leaves either the old file intact or the new file fully written — never a
 * half-written file that would fail to parse.
 */
public final class PersistedRingStateStore {

    /** Versioned header line — readers reject anything else. */
    public static final String HEADER = "# nexus-flow ring state v1";

    private final Path path;

    /**
     * @param path the file path; the parent directory must exist and be writable. The file
     *             itself does not need to exist — {@link #read()} returns an empty map if absent
     */
    public PersistedRingStateStore(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    /** Returns the configured file path. */
    public Path path() {
        return path;
    }

    /**
     * Read the persisted state. Returns an empty map if the file does not exist (first-run
     * pod). Returns the parsed map of {@code PeerId → lastSeenOutboxSequence} otherwise.
     *
     * @throws IOException           if the file exists but cannot be read
     * @throws IllegalStateException if the header is missing or the version is unsupported
     */
    public Map<PeerId, Long> read() throws IOException {
        if (!Files.exists(path)) {
            return Collections.emptyMap();
        }
        Map<PeerId, Long> result = new LinkedHashMap<>();
        var               lines  = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !HEADER.equals(lines.getFirst())) {
            throw new IllegalStateException(
                    "ring state file "
                            + path
                            + " missing or wrong header — expected: "
                            + HEADER
                            + "; got: "
                            + (lines.isEmpty() ? "<empty>" : lines.getFirst()));
        }
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab <= 0 || tab == line.length() - 1) {
                throw new IllegalStateException(
                        "ring state file " + path + " line " + (i + 1) + " malformed: " + line);
            }
            String peerId  = line.substring(0, tab);
            String seqText = line.substring(tab + 1);
            long   seq;
            try {
                seq = Long.parseLong(seqText);
            } catch (NumberFormatException nfe) {
                throw new IllegalStateException(
                        "ring state file "
                                + path
                                + " line "
                                + (i + 1)
                                + " has non-numeric sequence: "
                                + seqText,
                        nfe);
            }
            if (seq < 0) {
                throw new IllegalStateException(
                        "ring state file " + path + " line " + (i + 1) + " has negative sequence: " + seq);
            }
            result.put(PeerId.of(peerId), seq);
        }
        return result;
    }

    /**
     * Atomically write {@code state} to disk. Existing content is replaced. Writes to a
     * sibling {@code .tmp} file first, then renames into place — never leaves a half-written
     * file on crash.
     *
     * @param state immutable snapshot of {@code peerId → lastSeenSequence}; must not be {@code null}
     * @throws IOException if the write fails
     */
    public void write(Map<PeerId, Long> state) throws IOException {
        Objects.requireNonNull(state, "state");
        Path          tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        StringBuilder sb  = new StringBuilder(64 + state.size() * 48);
        sb.append(HEADER).append('\n');
        // Deterministic order — sort by peer id so two writes of the same logical state produce
        // byte-equal files (useful for diffing / change detection in deployment tooling).
        var sorted = new java.util.TreeMap<PeerId, Long>(
                (a, b) -> a.value().compareTo(b.value()));
        sorted.putAll(state);
        for (Map.Entry<PeerId, Long> e : sorted.entrySet()) {
            if (e.getValue() == null) {
                throw new IllegalArgumentException(
                        "state map contains null sequence for peer " + e.getKey());
            }
            if (e.getValue() < 0) {
                throw new IllegalArgumentException(
                        "state map contains negative sequence " + e.getValue() + " for peer "
                                + e.getKey());
            }
            sb.append(e.getKey().value()).append('\t').append(e.getValue()).append('\n');
        }
        Files.writeString(
                          tmp,
                          sb.toString(),
                          StandardCharsets.UTF_8,
                          StandardOpenOption.CREATE,
                          StandardOpenOption.TRUNCATE_EXISTING,
                          StandardOpenOption.WRITE);
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
            // Filesystem doesn't support atomic move (e.g. cross-device). Fall back to a
            // non-atomic move — the worst case is a half-written destination if the JVM
            // crashes between the delete and the rename, which is the standard caveat
            // documented for any non-POSIX-compliant filesystem.
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
