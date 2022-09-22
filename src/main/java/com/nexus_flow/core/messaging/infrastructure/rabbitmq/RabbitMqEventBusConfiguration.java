package com.nexus_flow.core.messaging.infrastructure.rabbitmq;

import com.nexus_flow.core.messaging.infrastructure.DomainEventSubscribersCollector;
import com.nexus_flow.core.messaging.infrastructure.DomainEventsCollector;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;
import java.util.stream.Collectors;

@Configuration
public class RabbitMqEventBusConfiguration {

    private final DomainEventSubscribersCollector domainEventSubscribersCollector;
    private final DomainEventsCollector           domainEventsCollector;
    private final RabbitMQProps                   rabbitMQProps;


    public RabbitMqEventBusConfiguration(
            DomainEventSubscribersCollector domainEventSubscribersCollector,
            DomainEventsCollector domainEventsCollector,
            RabbitMQProps rabbitMQProps) {
        this.domainEventSubscribersCollector = domainEventSubscribersCollector;
        this.domainEventsCollector           = domainEventsCollector;
        this.rabbitMQProps                   = rabbitMQProps;

    }


    @Bean
    public CachingConnectionFactory connection() {
        CachingConnectionFactory factory = new CachingConnectionFactory();

        factory.setHost(rabbitMQProps.getHost());
        factory.setPort(rabbitMQProps.getPort());
        Optional.ofNullable(rabbitMQProps.getVirtualHost()).ifPresent(factory::setVirtualHost);
        factory.setUsername(rabbitMQProps.getUsername());
        factory.setPassword(rabbitMQProps.getPassword());
        factory.getRabbitConnectionFactory().setAutomaticRecoveryEnabled(true);

        return factory;
    }

    @Bean
    public Declarables declaration() {
        String retryExchangeName      = RabbitMqExchangeNameFormatter.retry(rabbitMQProps.getExchange());
        String deadLetterExchangeName = RabbitMqExchangeNameFormatter.deadLetter(rabbitMQProps.getExchange());

        TopicExchange    domainEventsExchange           = new TopicExchange(rabbitMQProps.getExchange(), true, false);
        TopicExchange    retryDomainEventsExchange      = new TopicExchange(retryExchangeName, true, false);
        TopicExchange    deadLetterDomainEventsExchange = new TopicExchange(deadLetterExchangeName, true, false);
        List<Declarable> declarables                    = new ArrayList<>();
        declarables.add(domainEventsExchange);
        declarables.add(retryDomainEventsExchange);
        declarables.add(deadLetterDomainEventsExchange);

        Collection<Declarable> queuesAndBindings = declareQueuesAndBindings(
                domainEventsExchange,
                retryDomainEventsExchange,
                deadLetterDomainEventsExchange).stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        declarables.addAll(queuesAndBindings);

        return new Declarables(declarables);
    }

    private Collection<Collection<Declarable>> declareQueuesAndBindings(
            TopicExchange domainEventsExchange,
            TopicExchange retryDomainEventsExchange,
            TopicExchange deadLetterDomainEventsExchange
    ) {
        return domainEventSubscribersCollector.all().stream().map(eventSubscriberData -> {

            String queueName           = RabbitMqQueueNameFormatter.format(eventSubscriberData);
            String retryQueueName      = RabbitMqQueueNameFormatter.formatRetry(eventSubscriberData);
            String deadLetterQueueName = RabbitMqQueueNameFormatter.formatDeadLetter(eventSubscriberData);

            Queue queue = QueueBuilder.durable(queueName).build();
            Queue retryQueue = QueueBuilder.durable(retryQueueName).withArguments(
                    retryQueueArguments(domainEventsExchange, queueName)
            ).build();
            Queue deadLetterQueue = QueueBuilder.durable(deadLetterQueueName).build();

            // The following (same routing key as queue name) is in order to config retries

            Binding fromExchangeSameQueueBinding = BindingBuilder
                    .bind(queue)
                    .to(domainEventsExchange)
                    .with(queueName);

            Binding fromRetryExchangeSameQueueBinding = BindingBuilder
                    .bind(retryQueue)
                    .to(retryDomainEventsExchange)
                    .with(queueName);

            Binding fromDeadLetterExchangeSameQueueBinding = BindingBuilder
                    .bind(deadLetterQueue)
                    .to(deadLetterDomainEventsExchange)
                    .with(queueName);

            // Binds queues with the wright exchange
            List<Binding> fromExchangeDomainEventsBindings = eventSubscriberData.subscribedEvents().stream()
                    .map(domainEventClass -> {
                        String        eventName      = domainEventsCollector.forClass(domainEventClass);
                        TopicExchange sourceExchange = domainEventsCollector.getSourceExchange(eventName);
                        return BindingBuilder
                                .bind(queue)
                                .to(sourceExchange)
                                .with(eventName);
                    }).collect(Collectors.toList());

            List<Declarable> queuesAndBindings = new ArrayList<>();

            queuesAndBindings.add(queue);
            queuesAndBindings.add(fromExchangeSameQueueBinding);
            queuesAndBindings.addAll(fromExchangeDomainEventsBindings);

            queuesAndBindings.add(retryQueue);
            queuesAndBindings.add(fromRetryExchangeSameQueueBinding);

            queuesAndBindings.add(deadLetterQueue);
            queuesAndBindings.add(fromDeadLetterExchangeSameQueueBinding);

            return queuesAndBindings;

        }).collect(Collectors.toList());
    }

    /**
     * We are using retry queue by using original que as a kind of dead letter:
     * When message has expired (x-message-ttl time in milliseconds), the message is sent to the original queue (as if it
     * were a dead letter)<br>
     * See <a href="https://www.cloudamqp.com/docs/delayed-messages.html">Delayed messages with RabbitMQ</a><br>
     * "If a queue is declared with the x-dead-letter-exchange property
     * messages which is either rejected, nacked or the TTL for a message expires will be sent to the specified dead-letter-exchange,
     * and if you specify x-dead-letter-routing-key the routing key of the message with be changed when dead lettered."<br>
     * "By declaring a queue with the x-message-ttl property, messages will be discarded from the queue
     * if they haven't been consumed within the time specified."
     *
     * @param exchange
     * @param routingKey
     * @return
     */
    private Map<String, Object> retryQueueArguments(TopicExchange exchange, String routingKey) {
        HashMap<String, Object> arguments         = new HashMap<>();
        arguments.put("x-dead-letter-exchange", exchange.getName());
        arguments.put("x-dead-letter-routing-key", routingKey);
        arguments.put("x-message-ttl",1000);
        return arguments;
    }
}
