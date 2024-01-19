package org.nexusflow;

import org.nexusflow.core.cqrs.command.AcknowledgeMode;
import org.nexusflow.core.cqrs.command.Command;
import org.nexusflow.core.cqrs.command.CommandBus;
import org.nexusflow.core.cqrs.event.EventBus;
import org.nexusflow.core.cqrs.query.Query;
import org.nexusflow.core.cqrs.query.QueryBus;

import java.util.logging.Logger;

public class NexusFlowDemo {

    private static final Logger logger = Logger.getLogger(NexusFlowDemo.class.getName());

    public static void main(String[] args) {

        Command<MyCommand> command = Command.<MyCommand>builder()
                .body(new MyCommand("randomId", "newDescription", 1))
                .ackMode(AcknowledgeMode.AUTO)
                .priority(10)
                .build();

        EventBus eventBus = EventBus.getInstance();
        eventBus.register(new TestAggregateListener());

        CommandBus commandBus = CommandBus.getInstance();
        commandBus.register(new TestCommandHandler());

        commandBus.dispatch(command);

        Query<MyQuery> query = Query.<MyQuery>builder()
                .body(new MyQuery("randomId"))
                .build();

        QueryBus queryBus = QueryBus.getInstance();
        queryBus.register(new TestQueryHandler());

        String returned = queryBus.ask(query);
        logger.info(STR."QueryBus return: \{returned}");
    }
}