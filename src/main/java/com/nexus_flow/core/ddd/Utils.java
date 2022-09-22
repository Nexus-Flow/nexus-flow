package com.nexus_flow.core.ddd;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.base.CaseFormat;
import com.nexus_flow.core.ddd.exceptions.CouldNotDeserializeBody;
import com.nexus_flow.core.ddd.exceptions.CouldNotSerializeMap;
import com.nexus_flow.core.ddd.exceptions.DomainError;
import com.nexus_flow.core.ddd.exceptions.WrongFormat;

import java.io.Serializable;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

public final class Utils {

    public static final DateTimeFormatter DATE_TIME_FORMATTER          = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss:A Z")
            .withZone(ZoneOffset.UTC);
    public static final DateTimeFormatter DATE_TIME_FORMATTER_NO_NANOS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss Z")
            .withZone(ZoneOffset.UTC);
    public static final String            LINE_SEPARATOR               = "line.separator";

    private Utils() {
    }

    public static String dateToString(Instant utcDateTime) {
        return DATE_TIME_FORMATTER.format(utcDateTime);
    }

    public static String dateToStringNoNanos(Instant utcDateTime) {
        return DATE_TIME_FORMATTER_NO_NANOS.format(utcDateTime);
    }

    public static String now() {
        return dateToString(Instant.now());
    }

    public static ZonedDateTime nowZonedDateTime() {
        return ZonedDateTime.now(ZoneOffset.UTC);
    }

    public static String dateToString(ZonedDateTime zonedDateTime) {
        return dateToString(zonedDateTime.toInstant());
    }

    public static String dateToStringNoNanos(ZonedDateTime zonedDateTime) {
        return dateToStringNoNanos(zonedDateTime.toInstant());
    }

    public static String dateToString(Timestamp timestamp) {
        return dateToString(timestamp.toInstant());
    }

    public static ZonedDateTime stringToZonedDateTime(String stringDate) {
        return stringToZonedDateTime(stringDate, DATE_TIME_FORMATTER);
    }

    public static ZonedDateTime stringToZonedDateTime(String stringDate,
                                                      DateTimeFormatter dateTimeFormatter) {

        ZonedDateTime zoneDateTime;

        try {
            zoneDateTime = ZonedDateTime.from(dateTimeFormatter.parse(stringDate));
        } catch (Exception e) {
            throw new WrongFormat(stringDate + " has not the format " + dateTimeFormatter);
        }
        return zoneDateTime;
    }

    public static Timestamp stringToTimestamp(String stringDate) {

        Timestamp timestamp;

        ZonedDateTime zonedDateTime = stringToZonedDateTime(stringDate);

        try {
            timestamp = Timestamp.from(zonedDateTime.toInstant());
        } catch (Exception e) {
            throw new WrongFormat(stringDate + " has not the format " + DATE_TIME_FORMATTER);
        }
        return timestamp;
    }

    //**********************************************************************************

    /**
     * Converts immutable objects using all arguments' constructor (recovering their names)
     * Uses all jackson modules: parameters names, java time...
     *
     * @param entry       object to convert
     * @param targetClass class we want to convert to
     * @param <E>         entry type
     * @param <T>         target type
     * @return the result object
     */
    public static <E, T> T convertTo(E entry, Class<T> targetClass) {
        return convertTo(entry, targetClass, true);
    }

    public static <E, T> T convertTo(E entry, Class<T> targetClass, boolean failingOnUnknown) {
        if (entry == null) return null;
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, failingOnUnknown);
        return mapper.convertValue(entry, targetClass);
    }

    public static Map<String, Object> toMap(Object object) {
        ObjectMapper mapper = new ObjectMapper();
        JavaType     type   = mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
        return mapper.convertValue(object, type);
    }


    public static Map<String, String> toMapStringString(Object object) {
        ObjectMapper mapper = new ObjectMapper();
        JavaType     type   = mapper.getTypeFactory().constructMapType(Map.class, String.class, String.class);
        return mapper.convertValue(object, type);
    }

    public static Map<String, Serializable> toMapStringSerializable(Object object) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JavaType     type   = mapper.getTypeFactory().constructMapType(Map.class, String.class, Serializable.class);
        return mapper.convertValue(object, type);
    }

    public static Map<String, String> castToMapStringString(Map<String, Serializable> incomingMap) {
        return incomingMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> (String) entry.getValue()));

    }

    public static Map<String, Serializable> castToMapStringSerializable(Map<String, String> incomingMap) {
        return incomingMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> (Serializable) entry.getValue()));

    }

    public static <T> T convertPrimitivesTo(Map<String, Object> map,
                                            Class<T> targetClass) {
        return new ObjectMapper().convertValue(map, targetClass);
    }

    //**********************************************************************************

    public static String jsonEncode(Map<String, Serializable> map) {
        try {
            return new ObjectMapper().writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new CouldNotSerializeMap("Error encoding map into an string");
        }
    }

    public static Map<String, Serializable> jsonDecode(String body) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JavaType     type   = mapper.getTypeFactory().constructMapType(Map.class, String.class, Serializable.class);
            return mapper.readValue(body, type);
        } catch (Exception e) {
            throw new CouldNotDeserializeBody("Error decoding string body into an map");
        }
    }

    //**********************************************************************************

    public static String toSnake(String text) {
        return CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, text);
    }

    public static String toCamel(String text) {
        return CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, text);
    }

    public static String toCamelFirstLower(String text) {
        return CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, text);
    }


    //***********************************************************************************

    /**
     * Formats several Exceptions messages into one only message in different lines
     *
     * @param domainErrors      with the original messages
     * @param generalMessage    The general message to show on top
     * @param collapseIfOnlyOne if true, when there is only one message it would be shown in the top line,
     *                          the general message would be hidden
     * @return the composed message
     */
    public static String formatErrorMessage(Collection<DomainError> domainErrors,
                                            String generalMessage,
                                            boolean collapseIfOnlyOne) {

        if (domainErrors.size() == 1 && collapseIfOnlyOne) {
            return domainErrors.iterator().next().getErrorMessage();
        }

        StringBuilder finalMessage = new StringBuilder(generalMessage)
                .append(System.getProperty(LINE_SEPARATOR));

        domainErrors.forEach(domainError ->
                finalMessage.append("\t\t").append(domainError.getMessage())
                        .append(System.getProperty(LINE_SEPARATOR)));

        return String.valueOf(finalMessage);
    }

    //**********************************************************************************

    public static String concatDateTime(String date, String time) {
        if (date != null && !date.equals("") && time != null && !time.equals("")) {
            return date + " " + time + ":00 +0000";
        } else if (date != null && !date.equals("")) {
            return date + " 00:00:00 +0000";
        } else {
            return "";
        }
    }

}
