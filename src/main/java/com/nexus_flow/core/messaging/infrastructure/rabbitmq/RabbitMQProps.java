package com.nexus_flow.core.messaging.infrastructure.rabbitmq;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties
@ConfigurationProperties(prefix = "rabbitmq")
@Setter
@Getter
public class RabbitMQProps {
    private String  host;
    private Integer port;
    private String  username;
    private String  password;
    private String  exchange;
    private String  virtualHost;
    private Integer maxAttempts;
    private Integer maxWrongOrderAttempts;
    private Boolean consumerAutostart;
}
