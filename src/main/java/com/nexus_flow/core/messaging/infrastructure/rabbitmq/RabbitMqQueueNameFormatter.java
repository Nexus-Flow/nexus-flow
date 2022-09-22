package com.nexus_flow.core.messaging.infrastructure.rabbitmq;


import com.nexus_flow.core.messaging.infrastructure.DomainEventSubscriberData;

public final class RabbitMqQueueNameFormatter {
    public static String format(DomainEventSubscriberData subscriberData) {
        return subscriberData.formatRabbitMqQueueName();
    }

    public static String formatRetry(DomainEventSubscriberData subscriberData) {
        return String.format("retry.%s", format(subscriberData));
    }

    public static String formatDeadLetter(DomainEventSubscriberData subscriberData) {
        return String.format("dead_letter.%s", format(subscriberData));
    }

}
