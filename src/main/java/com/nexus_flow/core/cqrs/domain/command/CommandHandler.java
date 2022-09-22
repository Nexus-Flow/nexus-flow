package com.nexus_flow.core.cqrs.domain.command;

import org.springframework.messaging.handler.annotation.MessageMapping;

import java.lang.annotation.*;

@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@MessageMapping
@Documented
@Repeatable(CommandHandlers.class)
public @interface CommandHandler {

    String containerFactory() default "";

    String group() default "";

    String priority() default "";

    String concurrency() default "";

    String errorHandler() default "";

}
