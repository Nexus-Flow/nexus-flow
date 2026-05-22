package net.nexus_flow.core.cqrs.event;

import java.util.Objects;
import java.util.function.Consumer;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.types.TypeReference;

/**
 * Ergonomic DSL for assembling inline {@link DomainEventListener} instances without the
 * anonymous-subclass ceremony.
 *
 * <p>
 *
 * {@snippet :
 * var listener = DomainEventListener.forEvent(OrderShipped.class)
 *         .handle(event -> audit.record(event));
 *
 * eventBus.register(listener);
 * }
 *
 * <p>The {@link Class} parameter is a <em>type witness</em>: it both narrows the lambda parameter
 * type at the call site and provides the runtime routing key used by {@link EventBus#register}.
 *
 * <p>For listeners with internal state or complex lifecycle (e.g. saga participants) extend {@link
 * AbstractDomainEventListener} directly.
 */
public final class DomainEventListenerDsl {

    private DomainEventListenerDsl() {
        throw new AssertionError("No instances of DomainEventListenerDsl");
    }

    /** Entry point. See {@link DomainEventListener#forEvent(Class)} for the canonical call site. */
    static <E extends DomainEvent> EventStep<E> forEvent(Class<E> eventType) {
        Objects.requireNonNull(eventType, "eventType");
        return new EventStepImpl<>(new TypeReference<>(eventType));
    }

    /** Single-step builder: only the handle function is required. */
    public sealed interface EventStep<E extends DomainEvent> permits EventStepImpl {
        /**
         * Finalizes the DSL by supplying the listener body.
         *
         * @param handler the consumer that should handle events of type {@code E}
         * @return a listener ready to register with an {@link EventBus}
         */
        DomainEventListener<E> handle(Consumer<E> handler);
    }

    // Impls

    private record EventStepImpl<E extends DomainEvent>(TypeReference<E> typeRef)
            implements EventStep<E> {

        @Override
        public DomainEventListener<E> handle(Consumer<E> handler) {
            Objects.requireNonNull(handler, "handler");
            return new InlineEventListener<>(typeRef, handler, EventListenerOptions.defaults());
        }
    }

    /**
     * Concrete listener produced by the DSL. Inherits from {@link AbstractDomainEventListener} via
     * the direct-token constructor so the runtime routing key is preserved without anonymous-subclass
     * surgery.
     */
    static final class InlineEventListener<E extends DomainEvent> extends AbstractDomainEventListener<E> {

        private final Consumer<E>                                 handler;
        private final EventListenerOptions<? extends DomainEvent> options;

        InlineEventListener(
                TypeReference<E> typeRef,
                Consumer<E> handler,
                EventListenerOptions<? extends DomainEvent> options) {
            super(typeRef);
            this.handler = Objects.requireNonNull(handler, "handler");
            this.options = Objects.requireNonNull(options, "options");
        }

        @Override
        public void handle(E event) {
            handler.accept(event);
        }

        @Override
        public int order() {
            return options.order();
        }

        @Override
        public boolean parallelSafe() {
            return options.parallelSafe();
        }

        @Override
        public RetryPolicy retryPolicy() {
            return options.retryPolicy();
        }

        @Override
        @SuppressWarnings("unchecked")
        public EventListenerErrorHandler<E> errorHandler() {
            return (EventListenerErrorHandler<E>) options.errorHandler();
        }

        @Override
        public int concurrencyLevel() {
            return options.concurrencyLevel();
        }

        @Override
        public boolean deduplicateEnabled() {
            return options.deduplicateEnabled();
        }

        @Override
        public ListenerRateLimit rateLimit() {
            return options.rateLimit();
        }
    }
}
