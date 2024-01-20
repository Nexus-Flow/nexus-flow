package net.nexus_flow.core.cqrs.query;

@FunctionalInterface
public interface QueryErrorHandler {
    void onError(Throwable e);

}