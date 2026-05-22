package net.nexus_flow.core.outbox;

import java.io.Serial;
import java.util.Objects;

/**
 * raised by {@link OutboxStorage#append(OutboxRecord)} when an {@link IdempotencyKey} already
 * exists in the storage with a status other than {@link OutboxStatus#FAILED_TERMINAL}.
 *
 * <p>Re-append over a {@code FAILED_TERMINAL} row is the <em>manual replay</em> path and does
 * <strong>not</strong> raise this exception (storage replaces the terminal row with the new PENDING
 * row).
 */
public final class OutboxDuplicateKeyException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * {@code IdempotencyKey} is a value record; marked transient since exceptions are instantiated
     * in-process and never marshaled.
     */
    private final transient IdempotencyKey idempotencyKey;

    /**
     * Constructs the exception for the given duplicate key.
     *
     * @param idempotencyKey the key that already exists in storage with a non-terminal status; must
     *                       not be {@code null}
     */
    public OutboxDuplicateKeyException(IdempotencyKey idempotencyKey) {
        super(
              "Outbox already contains a non-terminal row for idempotencyKey="
                      + Objects.requireNonNull(idempotencyKey, "idempotencyKey"));
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * Returns the idempotency key that triggered this exception.
     *
     * @return the duplicate {@link IdempotencyKey}; never {@code null}
     */
    public IdempotencyKey idempotencyKey() {
        return idempotencyKey;
    }
}
