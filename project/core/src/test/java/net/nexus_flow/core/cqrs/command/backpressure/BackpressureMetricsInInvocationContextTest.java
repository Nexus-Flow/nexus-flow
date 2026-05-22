package net.nexus_flow.core.cqrs.command.backpressure;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.cqrs.command.*;
import net.nexus_flow.core.cqrs.command.exceptions.CommandHandlerExecutionError;
import net.nexus_flow.core.runtime.ErrorPolicy;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowRuntime;
import net.nexus_flow.core.runtime.dispatch.DispatchChain;
import net.nexus_flow.core.runtime.dispatch.DispatchInterceptor;
import net.nexus_flow.core.runtime.dispatch.InvocationContext;
import net.nexus_flow.core.runtime.interceptors.LoggingDispatchInterceptor;
import net.nexus_flow.core.runtime.result.DispatchResult;
import org.junit.jupiter.api.Test;

/** back-pressure metrics surfaced via {@link InvocationContext#attributes()}. */
class BackpressureMetricsInInvocationContextTest {

    record Metric(int n) {
    }

    @Test
    void enqueueStampsQueueDepthAndOccupancyOnInvocationContext() throws Exception {
        CommandSettings                       settings =
                CommandSettings.builder()
                        .backpressure(new HandlerBackpressureSettings(8, SaturationPolicy.BLOCK_CALLER, null))
                        .build();
        ReturnCommandHandler<Metric, Integer> handler  =
                new AbstractReturnCommandHandler<>() {
                                                                   @Override
                                                                   protected Integer handle(Metric command) {
                                                                       return command.n();
                                                                   }

                                                                   @Override
                                                                   public int getConcurrencyLevel() {
                                                                       return 1;
                                                                   }

                                                                   @Override
                                                                   public InitializationType getInitializationType() {
                                                                       return InitializationType.EAGER;
                                                                   }

                                                                   @Override
                                                                   public CommandSettings getCommandSettings() {
                                                                       return settings;
                                                                   }
                                                               };

        Map<String, Object> snapshot = new ConcurrentHashMap<>();
        DispatchInterceptor sniff    =
                new DispatchInterceptor() {
                                                 @Override
                                                 public <R> DispatchResult<R> intercept(InvocationContext ctx, DispatchChain<R> chain) {
                                                     DispatchResult<R> r = chain.proceed();
                                                     snapshot.putAll(ctx.attributes());
                                                     return r;
                                                 }
                                             };

        try (FlowRuntime runtime =
                FlowRuntime.builder()
                        .interceptor(sniff)
                        .interceptor(new LoggingDispatchInterceptor())
                        .build()) {
            runtime.commands().register(handler);
            try {
                DispatchResult<Integer> result =
                        runtime
                                .commands()
                                .dispatchAndReturnResult(
                                                         Command.<Metric>builder().body(new Metric(1)).build(),
                                                         ExecutionContext.root(),
                                                         ErrorPolicy.failFast());

                assertNotNull(result);
                assertInstanceOf(
                                 DispatchResult.Success.class,
                                 result,
                                 "Healthy dispatch must surface Success; got " + result);

                Object qd     = snapshot.get("handler.queueDepth");
                Object policy = snapshot.get("handler.saturationPolicy");
                assertEquals(8, qd, "handler.queueDepth must be present; snapshot=" + snapshot);
                assertEquals(
                             "BLOCK_CALLER",
                             policy,
                             "handler.saturationPolicy must be present; snapshot=" + snapshot);
                Object occ = snapshot.get("handler.queueOccupancy");
                assertNotNull(occ, "handler.queueOccupancy must be present; snapshot=" + snapshot);
                int occupancy = (Integer) occ;
                assertTrue(
                           occupancy >= 0 && occupancy <= 8 + 1,
                           "handler.queueOccupancy must be in [0, queueDepth+concurrency]; got " + occupancy);
            } finally {
                runtime.commands().unregister(handler);
            }
        }
    }

    @Test
    void rejectionBumpsRejectionsCounterAttribute() throws Exception {
        CountDownLatch                        release  = new CountDownLatch(1);
        CountDownLatch                        inflight = new CountDownLatch(1);
        CommandSettings                       settings =
                CommandSettings.builder()
                        .backpressure(new HandlerBackpressureSettings(0, SaturationPolicy.REJECT, null))
                        .build();
        ReturnCommandHandler<Metric, Integer> handler  =
                new AbstractReturnCommandHandler<>() {
                                                                   @Override
                                                                   protected Integer handle(Metric command) {
                                                                       inflight.countDown();
                                                                       try {
                                                                           release.await();
                                                                       } catch (InterruptedException ie) {
                                                                           Thread.currentThread().interrupt();
                                                                       }
                                                                       return command.n();
                                                                   }

                                                                   @Override
                                                                   public int getConcurrencyLevel() {
                                                                       return 1;
                                                                   }

                                                                   @Override
                                                                   public InitializationType getInitializationType() {
                                                                       return InitializationType.EAGER;
                                                                   }

                                                                   @Override
                                                                   public CommandSettings getCommandSettings() {
                                                                       return settings;
                                                                   }
                                                               };

        Map<String, Object> snapshot = new ConcurrentHashMap<>();
        DispatchInterceptor sniff    =
                new DispatchInterceptor() {
                                                 @Override
                                                 public <R> DispatchResult<R> intercept(InvocationContext ctx, DispatchChain<R> chain) {
                                                     DispatchResult<R> r = chain.proceed();
                                                     snapshot.putAll(ctx.attributes());
                                                     return r;
                                                 }
                                             };

        try (FlowRuntime runtime = FlowRuntime.builder().interceptor(sniff).build()) {
            runtime.commands().register(handler);
            try {
                Thread.ofVirtual()
                        .start(
                               () -> runtime
                                       .commands()
                                       .dispatchAndReturn(Command.<Metric>builder().body(new Metric(0)).build()));
                assertTrue(
                           inflight.await(2, TimeUnit.SECONDS), "First dispatch should have entered the handler");

                DispatchResult<Integer> r =
                        runtime
                                .commands()
                                .dispatchAndReturnResult(
                                                         Command.<Metric>builder().body(new Metric(1)).build(),
                                                         ExecutionContext.root(),
                                                         ErrorPolicy.failFast());

                assertInstanceOf(
                                 DispatchResult.Failure.class,
                                 r,
                                 "Saturated dispatch must surface DispatchResult.Failure; got " + r);
                DispatchResult.Failure<Integer> f     = (DispatchResult.Failure<Integer>) r;
                Throwable                       cause = f.cause();
                assertTrue(
                           cause instanceof SaturationRejectedException || (cause instanceof CommandHandlerExecutionError ce && ce
                                   .getCause() instanceof SaturationRejectedException),
                           "Failure cause must be SaturationRejectedException; got " + cause);

                Object count = snapshot.get("handler.rejections.count");
                assertNotNull(count, "handler.rejections.count must be stamped; snapshot=" + snapshot);
                assertTrue(((Long) count) >= 1L, "handler.rejections.count must be >= 1; got " + count);
            } finally {
                release.countDown();
                runtime.commands().unregister(handler);
            }
        }
    }
}
