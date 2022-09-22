package com.nexus_flow.core.cqrs.infrastructure.spring;

import com.nexus_flow.core.cqrs.domain.command.Command;
import com.nexus_flow.core.cqrs.domain.command.CommandBus;
import com.nexus_flow.core.cqrs.domain.command.exceptions.CommandHandlerExecutionError;
import com.nexus_flow.core.cqrs.domain.query.Query;
import com.nexus_flow.core.cqrs.domain.query.QueryBus;
import com.nexus_flow.core.cqrs.domain.query.exceptions.QueryHandlerExecutionError;

public abstract class ApiController {

    private final QueryBus queryBus;
    private final CommandBus commandBus;

    protected ApiController(QueryBus queryBus, CommandBus commandBus) {
        this.queryBus   = queryBus;
        this.commandBus = commandBus;
    }

    protected void dispatch(Command command) throws CommandHandlerExecutionError {
        commandBus.dispatch(command);
    }

    protected <R> R ask(Query query) throws QueryHandlerExecutionError {
        return queryBus.ask(query);
    }

}
