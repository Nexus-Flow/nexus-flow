package net.nexus_flow.core.cqrs.event;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Objects;
import net.nexus_flow.core.types.TypeReference;

/**
 * Super-type token that captures the {@code <E>} parameter of a {@link DomainEventListener} at
 * compile time and exposes it at runtime, working around Java's generic-type erasure.
 *
 * <p>Must be instantiated as an <strong>anonymous subclass</strong> (the {@link
 * AbstractDomainEventListener} pattern), or constructed via the copy constructor for builder-style
 * factories.
 *
 * @param <E> the captured domain-event type
 */
public abstract class EventTypeSignature<E> {

    private final TypeReference<E> eventType;

    /**
     * Resolves the captured event type from an anonymous subclass declaration.
     *
     * @throws IllegalStateException if the subclass does not retain a concrete type argument
     */
    protected EventTypeSignature() {
        Type superClass = getClass().getGenericSuperclass();
        if (!(superClass instanceof ParameterizedType parameterizedType)) {
            throw new IllegalStateException(
                    "EventTypeSignature must be instantiated as an anonymous subclass "
                            + "so the generic type argument is preserved in bytecode. Use: "
                            + "new EventTypeSignature<MyEvent>() {}");
        }
        Type[] types = parameterizedType.getActualTypeArguments();
        if (types.length == 0) {
            throw new IllegalStateException("EventTypeSignature requires one type argument");
        }
        rejectUnresolvedTypeVariable(types[0]);
        this.eventType = new TypeReference<>(types[0]);
    }

    /**
     * Copy constructor: reuses the resolved {@link TypeReference} of an already-constructed
     * signature, allowing helper types to inherit the captured type without requiring callers to
     * subclass them anonymously.
     */
    protected EventTypeSignature(EventTypeSignature<E> source) {
        Objects.requireNonNull(source, "source EventTypeSignature");
        this.eventType = source.eventType;
    }

    /**
     * Direct {@link TypeReference} constructor — used by DSL factories that already hold a
     * pre-validated token and do not need the anonymous-subclass reflection path.
     */
    EventTypeSignature(TypeReference<E> typeRef) {
        Objects.requireNonNull(typeRef, "typeRef");
        this.eventType = typeRef;
    }

    /**
     * Returns the resolved event type token captured for this listener signature.
     *
     * @return the reified event type token
     */
    public final TypeReference<E> getEventType() {
        return eventType;
    }

    private static void rejectUnresolvedTypeVariable(Type type) {
        if (type instanceof TypeVariable<?>) {
            throw new IllegalStateException(
                    "EventTypeSignature received an unresolved type variable ("
                            + type
                            + "). This typically happens when a generic static factory uses diamond + "
                            + "anonymous subclass — Java erases those type arguments at the call site. "
                            + "Use the typed DSL instead:\n"
                            + " DomainEventListener.forEvent(MyEvent.class).handle(e -> ...);");
        }
    }
}
