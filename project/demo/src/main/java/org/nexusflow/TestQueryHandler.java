package org.nexusflow;


import org.nexusflow.core.cqrs.query.AbstractQueryHandler;

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
