package com.nexus_flow.core.criteria.domain.filter;

import com.nexus_flow.core.ddd.exceptions.WrongFormat;
import lombok.Getter;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.function.Function;

@Getter
public enum FieldType {

    TIMESTAMP(Timestamp.class, s -> new SimpleDateFormat("dd/MM/yy HH:mm:ss").parse(s)),
    TIMESTAMP_INSTANT(Timestamp.class, s -> Timestamp.from(Instant.parse(s))),
    INTEGER(Integer.class, Integer::valueOf),
    LONG(Long.class, Long::valueOf),
    DOUBLE(Double.class, Double::valueOf),
    STRING(String.class, s -> s),
    BOOLEAN(Boolean.class, Boolean::valueOf);


    private final Class<? extends Comparable<?>> type;

    private final ThrowingFunction<String, ? extends Comparable<?>> constructor;


    FieldType(Class<? extends Comparable<?>> type,
              ThrowingFunction<String, ? extends Comparable<?>> constructor) {
        this.type        = type;
        this.constructor = constructor;
    }

    public static FieldType valueOfIgnoreCase(String name) {
        return FieldType.valueOf(name.toUpperCase());
    }

    @FunctionalInterface
    public interface ThrowingFunction<T, R extends Comparable<?>> extends Function<T, R> {

        @Override
        default R apply(final T elem) {
            try {
                return applyThrows(elem);
            } catch (final Exception e) {
                throw new WrongFormat("Error parsing value to target type");
            }
        }

        R applyThrows(T elem) throws Exception;
    }
}
