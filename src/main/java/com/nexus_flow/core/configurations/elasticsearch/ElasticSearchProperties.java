package com.nexus_flow.core.configurations.elasticsearch;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Configuration
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "nexus-flow.elasticsearch")
@Setter
@Getter
public class ElasticSearchProperties {
    private String                   host;
    private String                   port;
    private String                   dateTimePattern;
    private DateTimeFormatter        dateTimeFormatter;
    private String                   datePattern;
    private DateTimeFormatter        dateFormatter;
    private String                   userName;
    private String                   password;
    private String                   keystoreLocation;
    private String                   keystorePassword;
    @NestedConfigurationProperty
    private ElasticSearchPageDefault pageDefault;

    @PostConstruct
    private void createFormatters() {
        this.dateTimeFormatter = DateTimeFormatter
                .ofPattern(this.dateTimePattern)
                .withZone(ZoneOffset.UTC);
        this.dateFormatter     = DateTimeFormatter
                .ofPattern(this.datePattern)
                .withZone(ZoneOffset.UTC);
    }

    @Setter
    @Getter
    public static class ElasticSearchPageDefault {
        private int    number;
        private int    size;
        private String sortBy;
        private String sortDirection;
    }

}
