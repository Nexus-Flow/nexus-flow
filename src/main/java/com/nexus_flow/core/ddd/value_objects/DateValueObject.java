package com.nexus_flow.core.ddd.value_objects;

import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.ddd.exceptions.WrongFormat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores a date without time information. A String with internal pattern (yyyy-MM-dd) can also be provided
 */
public abstract class DateValueObject {

    public static final  String            ONLY_DATE            = "yyyy-MM-dd";
    private static final DateTimeFormatter formatter            = DateTimeFormatter.ofPattern(ONLY_DATE);
    public static final  LocalDate         MINIMUM_DATE_AS_NULL = fillMinimumDate();

    private static LocalDate fillMinimumDate() {
            Instant minInstant = Instant.ofEpochMilli(Long.MIN_VALUE);
            return LocalDate.ofInstant(minInstant, ZoneOffset.UTC);

    }

    // Watch out: it can be null (empty stringDate)
    protected LocalDate value;

    protected DateValueObject() {
    }

    protected DateValueObject(String value) {
        // Empty value is a correct option too
        this.value = (value == null || value.isBlank()) ? null : toLocalDate(value);
    }

    protected DateValueObject(LocalDate localDate) {
        // Empty value is a correct option too
        this.value = localDate;
    }

    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private LocalDate toLocalDate(String date) {
        try {
            return LocalDate.parse(date, formatter);
        } catch (IllegalArgumentException e) {
            throw new WrongFormat(date + " has not a correct pattern (" +
                    ONLY_DATE +
                    ") for " +
                    Utils.toSnake(this.getClass().getSimpleName()));
        }
    }

    // This is done because we need to deserialize from where a null value is not possible
    private void setValue(LocalDate localDate ) {
        if (localDate != null && localDate.equals(MINIMUM_DATE_AS_NULL)) localDate = null;
        this.value = localDate;
    }

    protected void checkNotNull() {
        if (value == null) {
            throw new WrongFormat(Utils.toSnake(this.getClass().getSimpleName()) + " can't be null");
        }
    }

    public Optional<LocalDate> optionalValue() {
        return Optional.ofNullable(value);
    }

    /**
     * Call only when value can't be null
     *
     * @return present optionalValue
     * @throws WrongFormat when called from a not null mandatory subclass
     */
    public LocalDate notNullValue() {
        checkNotNull();
        return value;
    }

    public LocalDate getValue() {
        return value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DateValueObject that = (DateValueObject) o;
        return Objects.equals(value, that.value);
    }

    // Watch out: to no regenerate automatically
    @Override
    public String toString() {
        if (value == null) return "";
        return formatter.format(value);
    }
}
