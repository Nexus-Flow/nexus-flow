package net.nexus_flow;


import net.nexus_flow.core.cqrs.query.AbstractQueryHandler;

public class TestQueryHandler extends AbstractQueryHandler<MyQuery, String> {

    public TestQueryHandler() {
        super();
    }

    @Override
    public String handle(MyQuery myCommand) {
        TestAggregate testAggregate = new TestAggregate("randomId", "newDescription", 2);
        return testAggregate.getDescription();
    }

}
