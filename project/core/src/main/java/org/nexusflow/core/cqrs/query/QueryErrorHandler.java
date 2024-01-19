package org.nexusflow.core.cqrs.query;

@FunctionalInterface
public interface QueryErrorHandler {
    void onError(Throwable e);

}