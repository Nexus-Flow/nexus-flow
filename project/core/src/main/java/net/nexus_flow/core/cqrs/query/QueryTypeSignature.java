package net.nexus_flow.core.cqrs.query;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Objects;
import net.nexus_flow.core.types.TypeReference;

/**
 * Super-type token that captures the {@code <T, R>} parameters of a {@link QueryHandler} at compile
 * time and exposes them at runtime.
 *
 * <p>Instantiate it as an <strong>anonymous subclass</strong> (the {@link AbstractQueryHandler}
 * pattern), or construct it with already-resolved type tokens for builder-style factories.
 *
 * @param <T> query payload type
 * @param <R> response type
 */
public abstract class QueryTypeSignature<T, R> {

    private final TypeReference<T> typeReference;
    private final TypeReference<R> returnType;

    /**
     * Creates a signature by resolving the concrete generic arguments from the subclass bytecode.
     *
     * @throws IllegalStateException if the subclass does not preserve concrete type arguments
     */
    protected QueryTypeSignature() {
        Type superClass = getClass().getGenericSuperclass();
        if (!(superClass instanceof ParameterizedType parameterizedType)) {
            throw new IllegalStateException(
                    "QueryTypeSignature must be instantiated as an anonymous subclass "
                            + "so the generic type arguments are preserved in bytecode. Use: "
                            + "new QueryTypeSignature<MyQuery, MyResponse>() {}");
        }
        Type[] types = parameterizedType.getActualTypeArguments();
        if (types.length <= 1) {
            throw new IllegalStateException("QueryTypeSignature requires two type arguments");
        }
        rejectUnresolvedTypeVariable(types[0], "query (T)");
        rejectUnresolvedTypeVariable(types[1], "response (R)");
        this.typeReference = new TypeReference<>(types[0]);
        this.returnType    = new TypeReference<>(types[1]);
    }

    /**
     * Creates a signature by copying already-resolved type tokens.
     *
     * @param source existing signature to copy from
     * @throws NullPointerException if {@code source} is {@code null}
     */
    protected QueryTypeSignature(QueryTypeSignature<T, R> source) {
        Objects.requireNonNull(source, "source QueryTypeSignature");
        this.typeReference = source.typeReference;
        this.returnType    = source.returnType;
    }

    /**
     * Creates a signature from pre-validated type tokens.
     *
     * @param queryType  concrete query body type token
     * @param returnType concrete response type token
     * @throws NullPointerException if either argument is {@code null}
     */
    QueryTypeSignature(TypeReference<T> queryType, TypeReference<R> returnType) {
        Objects.requireNonNull(queryType, "queryType");
        Objects.requireNonNull(returnType, "returnType");
        this.typeReference = queryType;
        this.returnType    = returnType;
    }

    /**
     * Returns the captured query-body type token.
     *
     * @return query-body type token
     */
    public final TypeReference<T> getQueryType() {
        return typeReference;
    }

    /**
     * Returns the captured response type token.
     *
     * @return response type token
     */
    public final TypeReference<R> getReturnType() {
        return returnType;
    }

    private static void rejectUnresolvedTypeVariable(Type type, String role) {
        if (type instanceof TypeVariable<?>) {
            throw new IllegalStateException(
                    "QueryTypeSignature received an unresolved type variable for "
                            + role
                            + " ("
                            + type
                            + "). Use the typed DSL instead:\n"
                            + " AbstractQueryHandler.forQuery(MyQuery.class)\n"
                            + " .returns(MyResponse.class)\n"
                            + " .handle(q -> ...);");
        }
    }
}
