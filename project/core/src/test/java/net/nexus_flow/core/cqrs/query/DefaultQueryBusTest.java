package net.nexus_flow.core.cqrs.query;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;
import net.nexus_flow.core.cqrs.query.exceptions.QueryHandlerExecutionError;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.FlowScope;
import net.nexus_flow.core.runtime.registry.HandlerRegistry;
import org.junit.jupiter.api.Test;

class DefaultQueryBusTest {

    record FindProduct(String id) {
    }

    record ReadContext(String id) {
    }

    record SlowLookup(String id) {
    }

    @Test
    void register_isSingleHandlerUnderConcurrentRegistration() throws Exception {
        DefaultQueryBus                                  bus      = (DefaultQueryBus) QueryBus.newInstance();
        List<AbstractQueryHandler<FindProduct, Integer>> handlers =
                IntStream.range(0, 8)
                        .mapToObj(
                                  index -> (AbstractQueryHandler<FindProduct, Integer>) new AbstractQueryHandler<FindProduct, Integer>() {
                                      @Override
                                      public Integer handle(FindProduct query) {
                                          return index;
                                      }
                                  })
                        .toList();
        CountDownLatch                                   ready    = new CountDownLatch(handlers.size());
        CountDownLatch                                   go       = new CountDownLatch(1);
        CountDownLatch                                   done     = new CountDownLatch(handlers.size());

        for (AbstractQueryHandler<FindProduct, Integer> handler : handlers) {
            Thread.startVirtualThread(
                                      () -> {
                                          ready.countDown();
                                          await(go);
                                          bus.register(handler);
                                          done.countDown();
                                      });
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, planSize(bus, FindProduct.class));
    }

    @Test
    void ask_propagatesCurrentExecutionContextToTimedHandler() {
        DefaultQueryBus bus = (DefaultQueryBus) QueryBus.newInstance();
        bus.register(
                     new AbstractQueryHandler<ReadContext, ExecutionContext>() {
                         @Override
                         public ExecutionContext handle(ReadContext query) {
                             return FlowScope.requireCurrent();
                         }

                         @Override
                         public QuerySettings settings() {
                             return QuerySettings.withTimeout(Duration.ofSeconds(1));
                         }
                     });
        ExecutionContext ctx = ExecutionContext.rootWithTimeout(Duration.ofSeconds(1));

        ExecutionContext seen =
                FlowScope.getWithContext(
                                         ctx, () -> bus.ask(Query.builder().body(new ReadContext("ctx")).build()));

        assertSame(ctx, seen);
    }

    @Test
    void ask_honorsCurrentExecutionContextDeadline() {
        DefaultQueryBus bus = (DefaultQueryBus) QueryBus.newInstance();
        bus.register(
                     new AbstractQueryHandler<SlowLookup, String>() {
                         @Override
                         public String handle(SlowLookup query) {
                             LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
                             return query.id();
                         }
                     });
        ExecutionContext ctx = ExecutionContext.rootWithTimeout(Duration.ofMillis(25));

        QueryHandlerExecutionError error =
                assertThrows(
                             QueryHandlerExecutionError.class,
                             () -> FlowScope.getWithContext(
                                                            ctx, () -> bus.ask(Query.builder().body(new SlowLookup("slow")).build())));

        assertInstanceOf(java.util.concurrent.TimeoutException.class, error.getCause());
    }

    @SuppressWarnings("unchecked")
    private static int planSize(DefaultQueryBus bus, Class<? extends Record> queryType) throws ReflectiveOperationException {
        Field registryField = DefaultQueryBus.class.getDeclaredField("registry");
        registryField.setAccessible(true);
        HandlerRegistry<Record, Object> registry =
                (HandlerRegistry<Record, Object>) registryField.get(bus);
        return registry.planFor(queryType).size();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
    }
}
