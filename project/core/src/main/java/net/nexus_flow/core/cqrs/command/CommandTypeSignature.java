package net.nexus_flow.core.cqrs.command;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Objects;
import net.nexus_flow.core.types.TypeReference;

/**
 * Super-type token that captures the {@code <T, R>} parameters of a command handler at compile time
 * and exposes them at runtime, working around Java's generic-type erasure.
 *
 * <p>Must be instantiated as an <strong>anonymous subclass</strong> so the JVM records the actual
 * type arguments in the {@code Signature} attribute of the synthetic class:
 *
 * <p>
 *
 * {@snippet :
 * var typeRef = new CommandTypeSignature<CreateOrder, OrderId>() {
 * };
 * var handler = CommandHandler.builder(typeRef)
 *         .withHandleFunctionResponse(cmd -> new OrderId(cmd.id()))
 *         .build();
 * }
 *
 * <p>This is the same pattern used by Guava's {@code TypeToken} and Jackson's {@code
 * TypeReference}: pure JDK, GraalVM-friendly, zero-dependency, and stable across LTS releases.
 *
 * <p>The two abstract handler base classes ({@link AbstractReturnCommandHandler}, {@link
 * AbstractNoReturnCommandHandler}) extend this class so they automatically capture their own
 * command type when subclassed anonymously — that is why {@code new
 * AbstractReturnCommandHandler<MyCommand, MyResponse>() { ... }} works without any extra ceremony.
 *
 * @param <T> the command payload type (must be a {@link Record})
 * @param <R> the response type ({@link Void} for no-return handlers)
 */
public abstract class CommandTypeSignature<T, R> {
    private final TypeReference<T> typeReference;
    private final TypeReference<R> returnType;

    /**
     * Reflective constructor: introspects the anonymous subclass's generic superclass to recover
     * {@code T} and {@code R}.
     *
     * @throws IllegalStateException if invoked without an anonymous subclass (i.e. when the type
     *                               arguments are not preserved in bytecode).
     */
    protected CommandTypeSignature() {
        Type superClass = getClass().getGenericSuperclass();
        if (!(superClass instanceof ParameterizedType parameterizedType)) {
            throw new IllegalStateException(
                    "CommandTypeSignature must be instantiated as an anonymous subclass "
                            + "so the generic type arguments are preserved in bytecode. Use: "
                            + "new CommandTypeSignature<MyCommand, MyResponse>() {}");
        }
        Type[] types = parameterizedType.getActualTypeArguments();
        rejectUnresolvedTypeVariable(types[0], "command (T)");
        if (types.length > 1) {
            rejectUnresolvedTypeVariable(types[1], "response (R)");
        }
        this.typeReference = new TypeReference<>(types[0]);
        this.returnType    =
                types.length > 1 ? new TypeReference<>(types[1]) : new TypeReference<>(Void.class);
    }

    /**
     * Fail-fast guard against unresolved type variables.
     *
     * <p>A {@code static <T,R> … of(Function<T,R>)} factory that internally does {@code new
     * AbstractReturnCommandHandler<>() {}} captures the method's own type variables instead of
     * concrete classes. The resulting handler would silently mis-route at registration time. This
     * method rejects that scenario early with a self-describing error that names the working
     * alternative.
     *
     * @param type the resolved (or unresolved) type argument to validate
     * @param role human-readable label used in the error message (e.g. {@code "command (T)"})
     * @throws IllegalStateException if {@code type} is still an unresolved {@link TypeVariable}
     */
    private static void rejectUnresolvedTypeVariable(Type type, String role) {
        if (type instanceof TypeVariable<?>) {
            throw new IllegalStateException(
                    "CommandTypeSignature received an unresolved type variable for "
                            + role
                            + " ("
                            + type
                            + "). This typically happens when a generic "
                            + "static factory uses diamond + anonymous subclass — Java erases "
                            + "those type arguments at the call site. Use the typed DSL instead:\n"
                            + " CommandHandler.forCommand(MyCommand.class)\n"
                            + " .returns(MyResponse.class)\n"
                            + " .handle(cmd -> ...);\n"
                            + "Or, for parameterised response types:\n"
                            + " CommandHandler.builder(new CommandTypeSignature<MyCommand, List<X>>(){})\n"
                            + " .withHandleFunctionResponse(cmd -> ...)\n"
                            + " .build();");
        }
    }

    /**
     * Copy constructor: reuses the resolved {@link TypeReference}s of an already-constructed
     * signature, allowing internal types (such as {@link CommandHandlerBuilder}) to inherit the
     * captured types without requiring callers to subclass them anonymously.
     */
    protected CommandTypeSignature(CommandTypeSignature<T, R> source) {
        Objects.requireNonNull(source, "source CommandTypeSignature");
        this.typeReference = source.typeReference;
        this.returnType    = source.returnType;
    }

    /**
     * Returns the captured command payload type.
     *
     * @return command payload type reference
     */
    public final TypeReference<T> getCommandType() {
        return typeReference;
    }

    /**
     * Returns the captured response type.
     *
     * @return response type reference
     */
    public final TypeReference<R> getReturnType() {
        return returnType;
    }
}
