package com.nexus_flow.core.messaging.infrastructure;

import com.nexus_flow.core.configurations.qualifier_resolver.QualifierWithPlaceholderResolverConfigurer;
import com.nexus_flow.core.messaging.domain.EventBus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;


@Configuration
public class PrimaryEventBusChooser {

    /**This bean chooses which is the primary event bus to inject it. It's done via a property in application.yml<br>
     * <b>IMPORTANT:</b> Its necessary to implement a placeholder resolver for Qualifier annotation. May be the IDE it's
     * not able to resolve the placeholder and marks it as an error;
     * the implementation: {@link QualifierWithPlaceholderResolverConfigurer}
     * @param eventBus
     * @return
     */
    @Primary
    @Bean
    public EventBus primaryEventBus(@Qualifier("${message-broker.event-bus}-event-bus") EventBus eventBus) {
        return eventBus;
    }
}
