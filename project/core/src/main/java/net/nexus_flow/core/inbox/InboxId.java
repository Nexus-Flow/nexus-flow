package net.nexus_flow.core.inbox;

import java.util.Objects;
import java.util.UUID;
import net.nexus_flow.core.runtime.ids.FastUuid;

/**
 * Opaque, storage-level surrogate primary key for an {@link InboxRecord} row.
 *
 * <p>The natural business key is the {@code (messageId, consumerId)} pair. {@code InboxId} is a
 * UUIDv4-backed surrogate that:
 *
 * <ul>
 * <li>works on any RDBMS without sequence or auto-increment configuration,
 * <li>is returned via {@link InboxClaim} so callers can invoke {@link InboxStorage#markProcessed}
 * / {@link InboxStorage#markFailed} without re-fetching the natural key,
 * <li>mirrors the design of {@code OutboxId} for consistency across the transactional messaging
 * tier.
 * </ul>
 */
public record InboxId(UUID value) {

    /** Validates that {@code value} is non-null. */
    public InboxId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Allocates a fresh {@code InboxId} backed by a random UUIDv4.
     *
     * <p>Called once per new delivery attempt, before the row is inserted into storage. The caller is
     * responsible for ensuring the generated ID is persisted atomically with the row.
     *
     * @return a new, unique {@code InboxId}
     */
    public static InboxId next() {
        return new InboxId(FastUuid.v4());
    }
}
