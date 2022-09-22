package com.nexus_flow.core.cqrs.domain.command;

import com.nexus_flow.core.cqrs.domain.command.exceptions.CommandHandlerExecutionError;

public interface CommandBus {
    void dispatch(Command command) throws CommandHandlerExecutionError;
}
