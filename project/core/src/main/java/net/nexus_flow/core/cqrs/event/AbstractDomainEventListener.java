package net.nexus_flow.core.cqrs.event;

import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.types.TypeReference;

/**
 * Convenience base for {@link DomainEventListener} implementations that carry their own state.
 * Captures the {@code <E>} type parameter via the inherited {@link EventTypeSignature} super-type
 * token, which the runtime uses to route dispatched events to the right listeners.
 *
 * <p>
 *
 * {@snippet :
 * var listener = new AbstractDomainEventListener<OrderShipped>() {
 *     &#64;Override
 *     public void handle(OrderShipped event) {
 *         audit.record(event);
 *     }
 * };
 * }
 *
 * <p>For inline lambda-style listeners prefer {@link DomainEventListener#forEvent(Class)}.
 */
public abstract non-sealed class AbstractDomainEventListener<E extends DomainEvent> extends EventTypeSignature<E> implements
        DomainEventListener<E> {

    /** Default anonymous-subclass constructor — resolves {@code <E>} from bytecode. */
    protected AbstractDomainEventListener() {
        super();
    }

    /**
     * Direct-token constructor for DSL factories that already hold a pre-validated {@link
     * TypeReference} and must not use the anonymous-subclass path.
     */
    AbstractDomainEventListener(TypeReference<E> typeRef) {
        super(typeRef);
    }

    /**
     * Handles a single domain event of the captured type.
     *
     * @param event the event to process
     */
    @Override
    public abstract void handle(E event);
}
