package org.nexusflow.core.cqrs.command;

@FunctionalInterface
public interface CommandValidator<T extends Record> {
    boolean validate(T commandBody);
}