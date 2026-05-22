package net.nexus_flow.core.runtime;

import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.result.DispatchResult;

/**
 * Execution mode of a dispatch.
 *
 * <p>The sealed hierarchy carries three variants: {@link Synchronous}, {@link
 * AsynchronousInMemory}, and {@link AsynchronousDurable}. All three are constructible and usable in
 * the runtime. Historically, {@code AsynchronousDurable} was declared but its constructor
 * unconditionally threw, allowing callers to pattern-match exhaustively without enabling durable
 * async semantics. This design allowed the durable path to the outbox / inbox infrastructure
 * ({@code net.nexus_flow.core.outbox.DurableDispatch}) to be wired incrementally, with the
 * construction-time rejection removed when ready.
 *
 * <p>The type itself is data; the runtime wiring that consumes it (executor selection, in-memory
 * async dispatch, outbox routing) lives in the dispatch / executor layer.
 */
public sealed interface ExecutionMode
        permits ExecutionMode.Synchronous,
        ExecutionMode.AsynchronousInMemory,
        ExecutionMode.AsynchronousDurable {

    /** Singleton accessor for {@link Synchronous}. */
    static Synchronous synchronous() {
        return Synchronous.INSTANCE;
    }

    /** Singleton accessor for {@link AsynchronousInMemory}. */
    static AsynchronousInMemory asynchronousInMemory() {
        return AsynchronousInMemory.INSTANCE;
    }

    /**
     * Construct the durable-async mode. The runtime wires this to the outbox / inbox infrastructure
     * via {@code net.nexus_flow.core.outbox.DurableDispatch}; construction is permitted and the
     * dispatch contract is honored by the outbox infrastructure.
     */
    static AsynchronousDurable asynchronousDurable() {
        return new AsynchronousDurable();
    }

    /**
     * The dispatch runs on the calling thread; nested dispatch is the synchronous chain (default for
     * command-from-command).
     */
    final class Synchronous implements ExecutionMode {
        static final Synchronous INSTANCE = new Synchronous();

        private Synchronous() {
        }

        @Override
        public String toString() {
            return "ExecutionMode.Synchronous";
        }
    }

    /**
     * The dispatch is forked onto a runtime-owned virtual-thread executor. Failures are still
     * returned as {@code DispatchResult}; durability is <em>not</em> a property of this mode (, ).
     */
    final class AsynchronousInMemory implements ExecutionMode {
        static final AsynchronousInMemory INSTANCE = new AsynchronousInMemory();

        private AsynchronousInMemory() {
        }

        @Override
        public String toString() {
            return "ExecutionMode.AsynchronousInMemory";
        }
    }

    /**
     * Durable-async dispatch — the message is appended to the durable outbox and the synchronous
     * result is a {@link DispatchResult.Accepted} carrying the persisted {@link MessageId}. Actual
     * delivery is performed asynchronously by the {@code OutboxWorker}.
     */
    final class AsynchronousDurable implements ExecutionMode {
        public AsynchronousDurable() {
            // no-op; durable async enabled.
        }

        @Override
        public String toString() {
            return "ExecutionMode.AsynchronousDurable";
        }
    }
}
