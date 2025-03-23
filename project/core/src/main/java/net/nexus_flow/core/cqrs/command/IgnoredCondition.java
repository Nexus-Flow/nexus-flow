package net.nexus_flow.core.cqrs.command;

public enum IgnoredCondition {
    PRIORITY_AND_CONCURRENCY,
    SAGA,
    ALL;
}