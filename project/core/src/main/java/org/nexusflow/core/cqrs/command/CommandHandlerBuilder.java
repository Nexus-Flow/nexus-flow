package org.nexusflow.core.cqrs.command;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

public class CommandHandlerBuilder<T extends Record, R> extends CommandTypeSignature<T, R> {

    Consumer<T> handleFunction;
    Function<T, R> handleFunctionWithResponse;
    int priority = 0;
    Set<AcknowledgeMode> ackModes = Set.of(AcknowledgeMode.AUTO);
    int concurrencyLevel = 0;
    CommandErrorHandler errorHandler;
    boolean canTriggerOtherCommands = true;
    Consumer<T> handleCompensation;

    public CommandHandlerBuilder() {
    }

//    public static <T extends Record, R> ResponseHandleFunctionStep<T, R> builder() {
//        return new CommandHandlerBuilder<T, R>().new ReturnBuilderSteps();
//    }
//
//    public static <T extends Record> HandleFunctionStep<T> builderNoReturn() {
//        return new CommandHandlerBuilder<T, Void>().new NoReturnBuilderSteps();
//    }

    public interface HandleFunctionStep<T extends Record> {
        NoReturnBuildStep<T> withHandleFunction(Consumer<T> handleFunction);
    }

    public interface ResponseHandleFunctionStep<T extends Record, R> {
        ReturnBuildStep<T, R> withHandleFunctionResponse(Function<T, R> handleFunctionWithResponse);
    }

    public interface BuildStep<T extends Record, R, S extends BuildStep<T, R, S>> {
        S withPriority(int priority);

        S withConcurrencyLevel(int concurrencyLevel);

        CommandHandler<T, R, ?> build();
    }

    public interface NoReturnBuildStep<T extends Record> extends BuildStep<T, Void, NoReturnBuildStep<T>> {
        @Override
        NoReturnCommandHandler<T> build();
    }

    public interface ReturnBuildStep<T extends Record, R> extends BuildStep<T, R, ReturnBuildStep<T, R>> {
        @Override
        ReturnCommandHandler<T, R> build();
    }

    public static final class NoReturnCommandHandlerImpl<T extends Record> implements NoReturnCommandHandlerInternal<T> {
        private final Consumer<T> handler;

        private NoReturnCommandHandlerImpl(Consumer<T> handler) {
            this.handler = handler;
        }

        @Override
        public Runnable handle(T command) {
            return () -> handler.accept(command);
        }
    }

    public static final class ReturnCommandHandlerImpl<T extends Record, R> implements ReturnCommandHandlerInternal<T, R> {
        private final Function<T, R> responseHandler;

        public ReturnCommandHandlerImpl(Function<T, R> responseHandler) {
            this.responseHandler = responseHandler;
        }

        @Override
        public Callable<R> handleAndReturn(T command) {
            return () -> responseHandler.apply(command);
        }
    }

    public class NoReturnBuilderSteps implements HandleFunctionStep<T>, NoReturnBuildStep<T> {

        @Override
        public NoReturnBuildStep<T> withHandleFunction(Consumer<T> handleFunction) {
            CommandHandlerBuilder.this.handleFunction = handleFunction;
            return this;
        }

        @Override
        public NoReturnBuildStep<T> withPriority(int priority) {
            CommandHandlerBuilder.this.priority = priority;
            return this;
        }

        @Override
        public NoReturnBuildStep<T> withConcurrencyLevel(int concurrencyLevel) {
            CommandHandlerBuilder.this.concurrencyLevel = concurrencyLevel;
            return this;
        }

        @Override
        public NoReturnCommandHandler<T> build() {
            if (CommandHandlerBuilder.this.handleFunction == null) {
                throw new IllegalStateException("Handle function must be defined for a No Return CommandHandler");
            }
            return new NoReturnCommandHandlerImpl<>(CommandHandlerBuilder.this.handleFunction);
        }
    }

    public class ReturnBuilderSteps implements ResponseHandleFunctionStep<T, R>, ReturnBuildStep<T, R> {

        @Override
        public ReturnBuildStep<T, R> withHandleFunctionResponse(Function<T, R> handleFunctionWithResponse) {
            CommandHandlerBuilder.this.handleFunctionWithResponse = handleFunctionWithResponse;
            return this;
        }

        @Override
        public ReturnBuildStep<T, R> withPriority(int priority) {
            CommandHandlerBuilder.this.priority = priority;
            return this;
        }

        @Override
        public ReturnBuildStep<T, R> withConcurrencyLevel(int concurrencyLevel) {
            CommandHandlerBuilder.this.concurrencyLevel = concurrencyLevel;
            return this;
        }

        @Override
        public ReturnCommandHandler<T, R> build() {
            if (CommandHandlerBuilder.this.handleFunctionWithResponse == null) {
                throw new RuntimeException("Handler not set");
            }
            return new ReturnCommandHandlerImpl<>(CommandHandlerBuilder.this.handleFunctionWithResponse);
        }
    }
}