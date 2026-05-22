package net.nexus_flow.core.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import net.nexus_flow.core.runtime.result.FlowCancellationException;

/**
 * Cooperative cancellation primitive scoped to a single {@link ExecutionContext}.
 *
 * <p>Children of a context observe the same token, so cancelling the parent is observable in every
 * nested handler that polls {@link #throwIfCancellationRequested()} at safe points.
 *
 * <p>Only the in-memory implementations are exposed through {@link #create()} and {@link
 * #neverCancelled()}. The interface defines the contract; alternative strategies (deadline-driven,
 * parent-linked, …) may be added in future releases without breaking existing callers.
 */
public interface CancellationToken {

    /** {@code true} once {@link #cancel()} has been observed by some thread. */
    boolean isCancellationRequested();

    /**
     * Mark the token as canceled. Idempotent on the {@link #create()} implementation, unsupported on
     * {@link #neverCancelled()}.
     */
    void cancel();

    /**
     * Throws {@link FlowCancellationException} iff cancellation has been requested. Uses the
     * stack-traceless {@link FlowCancellationException#CANCELLED} singleton — the check site's
     * stack trace would only point to the polling location (e.g., dispatcher infrastructure),
     * not the originating {@link #cancel()} caller, so the trace carries no actionable debug
     * value. Saves ~200 ns per check on the cancellation-poll hot path.
     */
    default void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw FlowCancellationException.CANCELLED;
        }
    }

    /** Mutable cancellation token suitable for ad-hoc dispatches. */
    static CancellationToken create() {
        return new Mutable();
    }

    /**
     * Singleton token that is permanently in the not-canceled state. Useful when a context cannot be
     * canceled (e.g., test fixtures, or integration paths where cancellation is not honored).
     */
    static CancellationToken neverCancelled() {
        return Never.INSTANCE;
    }

    /**
     * Standard mutable {@link CancellationToken} backed by an {@link
     * java.util.concurrent.atomic.AtomicBoolean}.
     *
     * <p>Calling {@link #cancel()} flips the internal flag from {@code false} to {@code true};
     * subsequent calls are no-ops. Thread-safe: the flag is implemented with an {@link
     * java.util.concurrent.atomic.AtomicBoolean}, so any thread that observes {@code true} on {@link
     * #isCancellationRequested()} is guaranteed to see any writes that happened before the
     * corresponding {@link #cancel()} call.
     */
    final class Mutable implements CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        Mutable() {
        }

        /** {@inheritDoc} */
        @Override
        public boolean isCancellationRequested() {
            return cancelled.get();
        }

        /**
         * {@inheritDoc}
         *
         * <p>Idempotent: multiple calls have the same effect as one.
         */
        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }

    /**
     * Permanently non-cancellable {@link CancellationToken} singleton.
     *
     * <p>{@link #isCancellationRequested()} always returns {@code false}. {@link #cancel()} throws
     * {@link UnsupportedOperationException} to surface misuse early — a "never-cancel" token is
     * intended for test fixtures and integration paths where cancellation is explicitly unsupported.
     */
    final class Never implements CancellationToken {
        static final Never INSTANCE = new Never();

        private Never() {
        }

        /** Always returns {@code false}. */
        @Override
        public boolean isCancellationRequested() {
            return false;
        }

        /**
         * Always throws {@link UnsupportedOperationException} — this token is permanently
         * non-cancellable by design.
         *
         * @throws UnsupportedOperationException unconditionally
         */
        @Override
        public void cancel() {
            throw new UnsupportedOperationException("CancellationToken.neverCancelled() is immutable");
        }
    }
}
