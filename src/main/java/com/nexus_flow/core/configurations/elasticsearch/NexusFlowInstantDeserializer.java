package com.nexus_flow.core.configurations.elasticsearch;

import com.fasterxml.jackson.datatype.jsr310.deser.InstantDeserializer;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class NexusFlowInstantDeserializer extends InstantDeserializer<ZonedDateTime> {

    protected NexusFlowInstantDeserializer(ElasticSearchProperties elasticSearchProperties) {
        super(ZonedDateTime.class,
              DateTimeFormatter
                      .ofPattern(elasticSearchProperties.getDateTimePattern())
                      .withZone(ZoneOffset.UTC),
              ZonedDateTime::from,
              a -> ZonedDateTime.ofInstant(Instant.ofEpochMilli(a.value), a.zoneId),
              a -> ZonedDateTime.ofInstant(Instant.ofEpochSecond(a.integer, a.fraction), a.zoneId),
              ZonedDateTime::withZoneSameInstant,
              false);// keep zero offset and Z separate since zones explicitly supported
    }
}
