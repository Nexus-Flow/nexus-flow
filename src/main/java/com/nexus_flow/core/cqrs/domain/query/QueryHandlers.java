package com.nexus_flow.core.cqrs.domain.query;

import org.springframework.messaging.handler.annotation.MessageMapping;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@MessageMapping
@Documented
public @interface QueryHandlers {
    QueryHandler[] value();
}

