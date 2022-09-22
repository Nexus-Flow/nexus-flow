package com.nexus_flow.core.cqrs.domain.command;

public interface CommandHandlerEndpoint {

    String getGroup();

    String getConcurrency();

}

