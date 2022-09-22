package com.nexus_flow.core.cqrs.domain.command;

import org.springframework.messaging.handler.annotation.MessageMapping;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@MessageMapping
@Documented
public @interface CommandHandlers {
    CommandHandler[] value();
}

