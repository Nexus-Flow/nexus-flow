package net.nexus_flow;


import net.nexus_flow.core.cqrs.command.AbstractNoReturnCommandHandler;
import net.nexus_flow.core.cqrs.command.InitializationType;

public class TestCommandHandler extends AbstractNoReturnCommandHandler<MyCommand> {

    public TestCommandHandler() {
        super();
    }

    @Override
    public void handle(MyCommand myCommand) {
        TestAggregate testAggregate = new TestAggregate(myCommand.id(), "oldDescription", 1); // For example, it can be retrieved from database
        testAggregate.update(myCommand.description(), myCommand.version());
    }

    @Override
    public int getPriority() {
        return 2;
    }

    @Override
    public boolean isSagaEnabled() {
        return true;
    }

    @Override
    public int getConcurrencyLevel() {
        return 2;
    }

    @Override
    public InitializationType getInitializationType() {
        return InitializationType.EAGER;
    }

}
