package com.nexus_flow.core.messaging.app.controller.consumers_managment;

import com.nexus_flow.core.cqrs.domain.command.CommandBus;
import com.nexus_flow.core.cqrs.domain.query.QueryBus;
import com.nexus_flow.core.cqrs.infrastructure.spring.ApiController;
import com.nexus_flow.core.messaging.domain.DomainEventsConsumer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import javassist.NotFoundException;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;

@RestController
@Tag(name = "CONSUMERS")
public final class ConsumersManagementPutController extends ApiController/* implements ApplicationContextAware*/ {

    private final ApplicationContext applicationContext;

    public ConsumersManagementPutController(QueryBus queryBus,
                                            CommandBus commandBus,
                                            ApplicationContext applicationContext) {
        super(queryBus, commandBus);

        this.applicationContext = applicationContext;
    }


    @PutMapping("/v1/consumers-management/{consumer}/{action}")
    @Operation(
            summary = "Starts or stop consumers",
            description = "Actual consumers are 'message-broker' (RabbitMQ) and 'database'. \n" +
                    "'consume' starts consumer and 'stop' stops it. "
    )
    public void index(@PathVariable String consumer, @PathVariable String action) throws Exception {

        try {
            DomainEventsConsumer eventsConsumer = (DomainEventsConsumer) applicationContext.getBean(consumer + "-consumer");

            Method actionMethod = eventsConsumer.getClass().getMethod(action, null);

            actionMethod.invoke(eventsConsumer, null);
        } catch (BeansException e) {
            throw new NotFoundException("Consumer '" + consumer + "' doesn't exist.");
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodException("Consumer '" + consumer + "' doesn't implement this operation: " + action);
        } catch (Exception e) {
            throw new RuntimeException("Unknown exception in ConsumersManagementPutController");
        }

    }


}
