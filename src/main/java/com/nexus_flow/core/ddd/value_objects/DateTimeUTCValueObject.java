package com.nexus_flow.core.ddd.value_objects;

import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.ddd.exceptions.WrongFormat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;


/**
 * Stores a date and time in UTC+0. If a ZonedDateTime is used to create it, it will be changed to UTC+0.
 * A String with internal pattern (yyyy-MM-dd HH:mm:ss Z) can also be provided
 */
public abstract class DateTimeUTCValueObject {

    public static final  String            UTC_DATE_AND_TIME = "yyyy-MM-dd HH:mm:ss Z";
    private static final DateTimeFormatter formatter         =
            DateTimeFormatter.ofPattern(UTC_DATE_AND_TIME).withZone(ZoneOffset.UTC);
    public static final  String MINIMUM_DATE_AS_NULL = fillMinimumDate();

    private static String fillMinimumDate() {
        Instant minInstant = Instant.ofEpochMilli(Long.MIN_VALUE);
        return formatter.format(ZonedDateTime.ofInstant(minInstant, ZoneOffset.UTC));

    }

    // Watch out: it can be null (empty stringDate)
    protected ZonedDateTime value;

    protected DateTimeUTCValueObject() {
    }

    protected DateTimeUTCValueObject(String value) {
        // Empty value is a correct option too
        this.value = (value == null || value.isBlank()) ? null : toZonedDateTime(value);
    }

    protected DateTimeUTCValueObject(LocalDateTime dateTime) {
        // Empty value is a correct option too
        this.value = dateTime == null ? null : ZonedDateTime.of(dateTime, ZoneOffset.UTC);
    }

    protected DateTimeUTCValueObject(ZonedDateTime zonedDateTime) {
        // Empty value is a correct option too
        this.value = zonedDateTime == null ? null : zonedDateTime.withZoneSameInstant(ZoneOffset.UTC);
    }

    public static ZonedDateTime nowUTC() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    private ZonedDateTime toZonedDateTime(String date) {
        if (date == null || date.equals(MINIMUM_DATE_AS_NULL)) return null;
        try {
            return ZonedDateTime.parse(date, formatter);
        } catch (IllegalArgumentException e) {
            throw new WrongFormat(date + " has not a correct pattern (" +
                    UTC_DATE_AND_TIME +
                    ") for " +
                    Utils.toSnake(this.getClass().getSimpleName()));
        }
    }

    protected void checkNotNull() {
        if (value == null) {
            throw new WrongFormat(Utils.toSnake(this.getClass().getSimpleName()) + " can't be null");
        }
    }

    public Optional<ZonedDateTime> optionalValue() {
        return Optional.ofNullable(value);
    }

    /**
     * Call only when value can't be null
     *
     * @return present optionalValue
     * @throws WrongFormat when called from a not null mandatory subclass
     */
    public ZonedDateTime notNullValue() {
        checkNotNull();
        return value;
    }

    public ZonedDateTime getValue() {
        return value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    // Watch out: to no regenerate automatically
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DateTimeUTCValueObject that = (DateTimeUTCValueObject) o;
        return Objects.equals(this.toString(), that.toString());
    }

    // Watch out: to no regenerate automatically
    @Override
    public String toString() {
        if (value == null) return "";
        return formatter.format(value);
    }
}
