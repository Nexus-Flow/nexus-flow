# Nexus Flow Library

## Overview
Welcome to the Nexus Flow library, a powerful tool for implementing Domain-Driven Design (DDD), Command Query Responsibility Segregation (CQRS), and Event-Driven Architecture (EDA) in your Java applications.

This documentation provides guidance on how to use the Nexus Flow library effectively, including examples for implementing various components.

## Table of Contents
1. [Command](#command)
2. [Command Handler](#command-handler)
3. [Query](#query)
4. [Query Handler](#query-handler)
5. [Aggregate](#aggregate)
6. [Domain Event](#domain-event)
7. [Event Listener](#event-listener)
8. [CommandBus](#commandbus)
9. [QueryBus](#querybus)
10. [EventBus](#eventbus)

## Command

In the context of CQRS (Command Query Responsibility Segregation), a command represents an intention or request to change the system's state. It is an abstraction that encapsulates the necessary information to perform a specific action in the application domain.

A command typically includes:

- **Acknowledge Mode:** Acknowledge mode specifies how the system should respond to the command. It can be configured to await automatic confirmation of execution or to operate asynchronously.

- **Priority:** Priority indicates the relative importance of the command compared to others in the processing queue. It helps manage the execution of commands, especially in high-load situations. Higher-priority commands can be processed before lower-priority ones.

These settings allow you to control the behavior and processing order of commands, which can be essential in scenarios where you need to manage concurrency and prioritize certain actions within your application.

```java
public record MyCommand(String id, String description, int version) {
    // You can add hashCode, toString, and equals methods here if needed.
}

Command<MyCommand> command = Command.<MyCommand>builder()
        .body(new MyCommand("randomId", "newDescription", 1))
        .ackMode(AcknowledgeMode.AUTO)
        .priority(10)
        .build();
```

### Command Handler

Command Handlers are responsible for processing commands and executing corresponding actions in the domain. They play a crucial role in the Command Query Responsibility Segregation (CQRS) architecture.

A Command Handler typically includes the following:

- **Concurrency Control:** The `getConcurrencyLevel()` method specifies the level of concurrency allowed for handling commands. This is important for managing the parallel execution of commands, especially in scenarios with high loads. A higher concurrency level allows multiple commands to be processed simultaneously.

- **Saga Pattern:** The `isSagaEnabled()` method determines if this handler is part of a saga. In the context of CQRS, a saga is a long-running transaction that spans multiple commands and events. If enabled, the command handler may participate in sagas, coordinating complex business processes.

- **Initialization Type:** The `getInitializationType()` method defines how the command handler is initialized. It can be set to either EAGER or LAZY initialization. EAGER initialization ensures that the handler is ready to process commands as soon as the application starts, while LAZY initialization defers initialization until the handler is needed. The choice depends on the specific requirements of your application.

- **Error Handling:** The `getErrorHandler()` method specifies the error handling strategy for this command handler. It allows you to define how errors are handled within the handler. In the provided example, unhandled errors are logged, but you can customize error handling according to your application's needs.

- **Compensation Logic:** The `handleCompensation()` method is used to define compensation logic in case of a failure. It is essential for handling rollbacks or compensating actions when a command fails to execute successfully.

Here's an example of a command handler with these overrides:

```java
public class TestCommandHandler extends AbstractNoReturnCommandHandler<MyCommand> {

    @Override
    public void handle(MyCommand myCommand) {
        // Add your Java code here to process the command.
    }

    @Override
    public int getConcurrencyLevel() {
        return 2; // Configure the desired concurrency level here.
    }

    @Override
    public boolean isSagaEnabled() {
        return true; // Enable saga participation if needed.
    }

    @Override
    public InitializationType getInitializationType() {
        return InitializationType.LAZY; // Choose between EAGER or LAZY initialization.
    }

    @Override
    public CommandErrorHandler getErrorHandler() {
        return e -> {
            // Customize error handling as needed.
            Logger logger = Logger.getLogger(this.getClass().getName());
            logger.log(Level.SEVERE, "Unhandled error: ", e);
        };
    }

    @Override
    public void handleCompensation(MyCommand myCommand) {
        // Implement compensation logic for rollbacks if necessary.
    }
}
```

## Query

In the context of CQRS (Command Query Responsibility Segregation), a query represents a request for information from the system. Unlike commands that change the system's state, queries are read-only operations focused on retrieving data.

A query typically includes:

- **Body:** The body of the query encapsulates the necessary information to retrieve specific data from the application domain. It defines the query's parameters and criteria.

Queries are essential for retrieving information from the application, making it available for presentation, reporting, or any operation where data access is required.

```java
public record MyQuery(String id) {
    // You can add hashCode, toString, and equals methods here if needed.
}

Query<MyQuery> query = Query.<MyQuery>builder()
        .body(new MyQuery("randomId"))
        .build();
```

## Query Handler

Query Handlers play a crucial role in the CQRS (Command Query Responsibility Segregation) architecture by handling queries and providing the requested information from the system. Unlike commands that change the system's state, queries are read-only operations focused on retrieving data. Query Handlers act as intermediaries between the application and the data store, ensuring that the right data is fetched and returned in response to queries.

### TestQueryHandler Example

In this example, we have a `TestQueryHandler` responsible for handling queries of type `MyQuery` and returning a result of type `String`. The `handle` method in the `TestQueryHandler` is where you would add your Java code to process the query and provide the requested information.

```java
public class TestQueryHandler extends AbstractQueryHandler<MyQuery, String> {

    @Override
    public String handle(MyQuery myQuery) {
        // Add your Java code here to process the query and return the desired information.
        // For instance, you can query a database, perform calculations, or access other data sources.
    }
}
```

## Aggregate

In the context of Nexus Flow, Aggregates are fundamental domain objects that play a critical role in domain-driven design (DDD). They encapsulate both the state and related logic for a group of entities, acting as cohesive units responsible for maintaining data integrity and enforcing business rules within your application.

The `TestAggregate` class serves as an illustrative example of an Aggregate in Nexus Flow. It allows you to:

- Maintain the state and behavior of a group of entities.
- Enforce data integrity and business rules.
- Capture and record domain events using the `recordEvent` method.

```java
public class TestAggregate extends Aggregate {

    private final String id;
    private String description;
    private int version;

    public TestAggregate(String id, String description, int version) {
        this.id = id;
        this.description = description;
        this.version = version;
    }

    public void update(String description, int version) {
        this.description = description;
        this.version = version;
        recordEvent(new UpdateTestDomainEvent(this.id, this.description, this.version));
    }

    // You can add hashCode, toString, and equals methods here if needed.
}

```
## Domain Event

In the context of Nexus Flow, Domain Events are essential components that represent significant occurrences within the application's domain. These events serve as valuable markers of state changes and pivotal actions within your application.

The `UpdateTestDomainEvent` class is an example of a Domain Event in Nexus Flow. It should be constructed within an Aggregate using the `recordEvent` method, indicating noteworthy changes in the Aggregate's state. This approach ensures that all relevant domain events are properly captured and recorded for further processing, auditing, and historical analysis within your application.

```java
public class UpdateTestDomainEvent extends AbstractDomainEvent {
    private final String description;
    private final int version;

    protected UpdateTestDomainEvent(String aggregateId,
                                    String description,
                                    int version) {
        super(aggregateId);
        this.description = description;
        this.version = version;
    }

    // You can add hashCode, toString, and equals methods here if needed.
}

```
## Event Listener

In the context of Nexus Flow, Event Listeners play a crucial role in processing domain events and orchestrating related actions. These listeners are responsible for reacting to specific domain events and executing predefined logic based on the event's content.

In the example of the `TestAggregateListener` class, it extends the `AbstractDomainEventListener` and is designed to handle `UpdateTestDomainEvent` instances. When a `UpdateTestDomainEvent` is triggered, this listener's `handle` method is invoked, allowing you to define custom actions to be taken in response to the event.

The `order` method within an event listener serves as a mechanism to control the execution order of multiple event listeners for the same event type. By specifying different order values, you can control whether events should be processed sequentially or in parallel. Events with the same order value are processed in parallel, while events with different order values are executed sequentially.

For example, consider a scenario where you have four event handlers for the same event:

- Event Handler with Order 1
- Event Handler with Order 2
- Event Handler with Order 2
- Event Handler with Order 3

In this case, the event processing sequence would be as follows:

1. Event Handler with Order 1: Executed first.
2. Event Handlers with Order 2: Executed in parallel since they share the same order.
3. Event Handler with Order 3: Executed after the previous handlers have completed.

This order control mechanism allows you to fine-tune the behavior of your application when processing domain events, ensuring that events are handled in a manner that aligns with your business requirements and concurrency constraints.
```java
public class TestAggregateListener extends AbstractDomainEventListener<UpdateTestDomainEvent> {
    private static final Logger logger = Logger.getLogger(TestAggregateListener.class.getName());

    @Override
    public void handle(UpdateTestDomainEvent event) {
        logger.info("UpdateTestDomainEvent processed");
    }

    @Override
    public int order() {
        return 1;
    }
}
```

## CommandBus

In Nexus Flow, the **CommandBus** is a critical component responsible for handling and dispatching commands to the appropriate command handlers. It serves as a central coordinator for command processing within the CQRS architecture.

```java
CommandBus commandBus = CommandBus.getInstance();
commandBus.register(new TestCommandHandler()); // With no return
commandBus.register(new TestCommandHandler2()); // With return
commandBus.dispatch(command);
String value = commandBus.dispatchAndReturn(command);
```
## QueryBus

The **QueryBus** in Nexus Flow is responsible for processing queries and providing the requested information. It facilitates the separation of commands and queries in the CQRS pattern.

```java
QueryBus queryBus = QueryBus.getInstance();
queryBus.register(new TestQueryHandler());
String value = queryBus.ask(query);
```
## EventBus

The **EventBus** is a core component in Nexus Flow for managing domain events and notifying registered event listeners. It ensures that events are appropriately distributed and handled within the application.

```java
EventBus eventBus = EventBus.getInstance();
eventBus.register(new TestAggregateListener());
```
These bus components enable you to maintain a clear separation of concerns between commands, queries, and events in your application. By registering handlers and dispatching or handling commands, queries, and events, you can efficiently manage the flow of information and actions in your system.

## Contributing
We welcome contributions from the community to help improve the Nexus Flow library. If you would like to contribute, here's how you can get started:

1. Fork the repository.
2. Create a new branch for your feature or bug fix.
3. Make your changes and commit them to your branch.
4. Submit a pull request to the `master` branch of this repository.

## Issues
If you encounter any issues or have suggestions for improvement, please open an issue on this repository.

We appreciate your feedback and contributions to make Nexus Flow even better.
