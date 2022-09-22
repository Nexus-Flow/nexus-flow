# NEXUS FLOW CORE

## _**Intro**_
The intention of this library is to give functionality to any project for all bounded contexts in ANY project.
It provides support to follow the patterns:
- [Domain Driven Design (DDD)](#1-domain-driven-design-ddd)
- [Command Query Responsibility Segregation (CQRS)](#2-command-query-responsibility-segregation-cqrs)
- [Event Driven Design (EDD)](#3-messaging-functionality-event-driven-design)
- [Criteria](#4-criteria)
- [Object Mother for tests](#5-tests)

It also includes some configuration for [Flyway](#6-flyway) and for [testing](#5-tests)

To use it must be included as a _dependency_ in your project pom.

Because we follow dependency injection and hexagonal architecture, it's common the division between 'domain' where we
include the interfaces and 'infrastructure', where implementations are.

***IMPORTANT**!* In order to work properly, Spring Boot application class in your project must be placed in the package com.nexus_flow
For instance:
com.nexus_flow.app --> your application
com.nexus_flow --> SpringBootApplication.class
This is only for JPA to detect domain event repository (where events are persisted if main publisher is not working)
and can create the table.


## 1. Domain Driven Design (DDD)
This package provides:
- Abstract Value Objects: By extending them we can create one specific value object of the most common wrapped types. Some of them also include handly
  methods for check incoming value. For instance:

```java
public class AttachmentId extends StringValueObject {

    public AttachmentId(String value) {
        super(value);
        checkNotNull();
    }
}
```
One special case is ``AggregateRoot`` which provides functionality to an aggregate root to register events.


- Utils: to manage dates, JSONs, maps, snake_case...

- Annotations: To decouple another third party annotations like Spring `@Services` and @`Repository`. These annotations
  must be scanned by Spring Boot:
```java
@SpringBootApplication
@ComponentScan(
        includeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION,
                classes = {
                        ResearchService.class,
                        ResearchCommandHandler.class,
                        ResearchQueryHandler.class,
                        ResearchRepository.class,
                        ResearchBus.class}),
        basePackages = {
                "com.nexus_flow.app",
                "com.nexus_flow.shared_domain",
                "com.nexus_flow.core"})
public class ResearchApplication {

    public static void main(String[] args) {

        SpringApplication.run(ResearchApplication.class, args);
    }
}
```


- Exceptions: common exceptions used everywhere like WrongFormat, DomainError, DataInconsistency. Some of them are
  abstract to be extended and used more concise, for instance: ``YearInconsistency extends DataInconsistency``


- SharedErrorHandlingController: to control that exceptions and map them to an HTTP status.


## 2. Command Query Responsibility Segregation (CQRS)
CQRS allows us to achieve more decouple between modules within a bounded context or microservice, thanks to a _**command bus**_
and a _**query bus**_. It also allows us to  create a write model for the owner of some data and different read models for
the same data (updated via events) depending on the needs. This also provides a better performance because we always have
the queried data, there is no need to make joins from several tables, for instance.
Package includes:
- Decoupled annotations (like in DDD, see above)
- Query and Command buses interfaces and in-memory implementation of them.
- Command and CommandHandler interfaces (and Query and QueryHandler): by implementing them we create command/command handler
- Functionality that via reflection **maps which CommandHandler handles which Command** (and QueryHandler - Query)
  For instance, next class is a Query Handler that handles FindResearchQuery and returns ResearchResponse:

``
public final class FindResearchQueryHandler implements QueryHandler<FindResearchQuery, ResearchResponse> {
``


## 3. Messaging functionality (Event Driven Design)
This package provides messaging functionality to accomplish more decoupled bounded context and/or microservices. This way
we avoid a direct http communication between contexts and/or microservices.

In the domain package it's included:
- Annotation to declare an _event subscriber_
- Interfaces for _event bus_ (to delivery messages) and _event consumer_.
- Abstract class DomainEvent, by extending it and implementing required methods we have a new domain event
  (we have to include it in a common place, a shared kernel)

Infrastructure package and **_HOW IT WORKS:_**

We have to implementations for manage messages: the message broker  **_RabbitMQ_** and Spring (only working within the same application, so it's only used
when RabbitMQ is not available).

We can choose one or another by a property in the application.yml:
```yaml
message-broker.event-bus: rabbitmq #possible values are rabbitmq or spring
```

RabbitMQ configuration is also via properties:
```yaml
rabbitmq:
  host: localhost
  port: 5672
  virtual-host: local-1 #--> It is not mandatory. Default / if omitted
  username: @application.name@
  password: no-admin
  exchange: @application.name@ #--> this is mandatory to correct automatic configuration (see example-pom.xml in resources)
  max-attempts: 5
  consumer-autostart: true #--> if it is set to false, we have to start consumer with the provided endpoint
```

**_IMPORTANT_**: IN ORDER TO GET A CORRECT AUTOMATIC CONFIGURATION WE SHOULD FOLLOW NAMES PROPOSED IN THE EXAMPLE-POM (resources)

By deploying a microservice with this shared bounded as dependency it:

- Creates an exchange to publish de events
- Creates one queue for each subscriber (detects ``@DomainEventSubscriber`` classes, that must implement method call 'on', see example below)
- Creates a retry and a dead letter queue for each queue. After max-attempts of delivery the message, it's sent to dead letter.
- Provides an auto starting event consumer (configurable in the above properties)
- Provides an endpoint to start or stop event consumers (it's strongly advised to stop consumers before stop an application)
- Creates a table for the fail back postgres event bus (via JPA)



```java
@ResearchService
@DomainEventSubscriber({DocumentUploadStartedDomainEvent.class, DocumentUploadedDomainEvent.class}) //<-- subscriber to these events
public class AddOrUpdateAttachmentOnDocumentUploadStatusChanged {

    private final AttachmentAdder           adder;
    private final AttachmentStatusUpdater   statusUpdater;
    private final AttachmentsIsReadyChecker attachmentsIsReadyChecker;
    private final AttachmentFinder          attachmentFinder;


    public AddOrUpdateAttachmentOnDocumentUploadStatusChanged(AttachmentAdder adder,
                                                              AttachmentStatusUpdater statusUpdater,
                                                              AttachmentsIsReadyChecker attachmentsIsReadyChecker,
                                                              AttachmentFinder attachmentFinder) {
        this.adder                     = adder;
        this.statusUpdater             = statusUpdater;
        this.attachmentsIsReadyChecker = attachmentsIsReadyChecker;
        this.attachmentFinder          = attachmentFinder;
    }

    @EventListener //<-- listener for this ↓ event, method 'on'
    public void on(DocumentUploadStartedDomainEvent event) throws NoSuchMethodException {
       //body
    }

    @EventListener
    public void on(DocumentUploadedDomainEvent event) throws NoSuchMethodException {
       //body
    }
}
```

This package also provides a _**FAIL BACK EVENT BUS**_ with postgres: if Rabbit is not working, events will be stored in a local
database. When RabbitMQ is back, automatically is detected and events from the database will be published to Rabbit. It also has
retries and a kind of dead letter if a message couldn't be publish a max number of times. As said before, it's also important to
follow names proposed in the example-pom (resources), so that JPA knows what default schema is.

If a message is sent to dead letter or can't be published (not for Rabbit cause) this library will publish own events in order to inform.

## 4. Criteria
Criteria pattern allows fetching elements by providing filters with domain concepts ("Search elements where 'version' is lower or equals than 2").
This domain concepts are then translated to our persistence system language.
By now it's only implemented JPA Specification converter for Postgres, but, since JPA can manage SQL databases, it will probably work in another SQL database (not tested).
This converter checks if the column exists and if it is not of the type json or jsonb, and throws an exception if so, because
by now it is not supported; we have to search within a json with a native query.


## 5. Tests
Tests package provide us a lot of Object Mothers (a kind of Factory pattern) for the most common object types to create them
randomly or with values. This pattern avoids code duplicity and decouples an aggregate from its test. If we change the aggregate,
we only need to change its Aggregate Mother and not each and every place where aggregate appears.
For instance, this class creates a random name:
```java
public final class PersonMother {
    public static String random() {
        return MotherCreator.random().name().fullName();
    }
}
```
By extending InfrastructureTestCase or UnitTestCase we create this kind of test and get some functionality. For instance,
infrastructure tests are filtered during the project building phase, because it may not exist an enviroment. They can be
launch afterwards with a Maven command (see Example-pom.xml in resources)
```
mvn test -Dspring.profiles.active=dev -Pintegration_tester
```


## 6. Flyway
If using Flyway to keep database structure consistency and JPA to manage this database, here is configured so Flyway launches
its scripts just after JPA has created the need structure of tables. If not, Flyway could try to insert data into a non-existing
table or with a different structure.

  