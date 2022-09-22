package com.nexus_flow.core.domain;

import com.nexus_flow.core.ddd.Utils;
import com.nexus_flow.core.ddd.value_objects.DateTimeUTCValueObject;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.concurrent.TimeUnit;

public final class DateTimeUTCMother {

    public static final String            DATE_AND_TIME            = DateTimeUTCValueObject.UTC_DATE_AND_TIME;
    public static final DateTimeFormatter DATE_AND_TIME_WITH_NANOS = Utils.DATE_TIME_FORMATTER;


    public static ZonedDateTime nowUTC() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    public static ZonedDateTime randomZonedDateTimeUTC() {
        return ZonedDateTime.ofInstant(randomDate().toInstant(), ZoneOffset.UTC);
    }

    public static ZonedDateTime randomZonedDateTimeUTCWithYear(Integer year) {
        return ZonedDateTime.ofInstant(randomDate().toInstant(), ZoneOffset.UTC).withYear(year);
    }

    public static String randomPastZonedDateTimeUTC() {
        return randomZonedDateTimeUTC().format(DateTimeFormatter.ofPattern(DATE_AND_TIME).withZone(ZoneOffset.UTC));
    }

    private static Date randomDate() {
        return MotherCreator.random().date().past(300, TimeUnit.DAYS);
    }

    public static ZonedDateTime randomPastForEvents() {
        return ZonedDateTime.ofInstant(randomDate().toInstant(), ZoneOffset.UTC);
    }

    public static String randomStringPastForEvents() {
        return Utils.dateToString(randomPastForEvents());
    }
}
