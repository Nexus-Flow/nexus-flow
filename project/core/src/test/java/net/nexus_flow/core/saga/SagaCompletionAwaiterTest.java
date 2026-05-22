package net.nexus_flow.core.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class SagaCompletionAwaiterTest {

    private static SagaState freshRunning(String type, String correlationKey) {
        // InMemorySagaStorage routes saves by `_correlationKey` so the fresh state must
        // carry it from the start.
        return SagaState.fresh(new SagaId(java.util.UUID.randomUUID()), type, Instant.now())
                .next(Map.of("_correlationKey", correlationKey),
                      SagaStatus.RUNNING, 0L, Instant.now());
    }

    private static SagaState toTerminal(SagaState base, SagaStatus terminal) {
        return base.next(base.data(), terminal, base.lastProcessedGlobalPosition(), Instant.now());
    }

    @Test
    void awaitCompletion_returnsImmediately_whenSagaAlreadyTerminal() throws Exception {
        InMemorySagaStorage storage  = new InMemorySagaStorage();
        SagaState           terminal = toTerminal(freshRunning("Demo", "k"), SagaStatus.COMPLETED);
        storage.save(terminal, 0L);
        try (SagaCompletionAwaiter awaiter =
                SagaCompletionAwaiter.builder(storage).build()) {
            SagaState result = awaiter.awaitCompletion(
                                                       "Demo", "k", Duration.ofSeconds(1)).get(2, TimeUnit.SECONDS);
            assertEquals(SagaStatus.COMPLETED, result.status());
        }
    }

    @Test
    void awaitCompletion_blocksUntilTerminal_thenResolves() throws Exception {
        InMemorySagaStorage storage = new InMemorySagaStorage();
        SagaState           initial = freshRunning("Demo", "k");
        storage.save(initial, 0L);
        try (SagaCompletionAwaiter awaiter =
                SagaCompletionAwaiter.builder(storage)
                        .pollInterval(Duration.ofMillis(20))
                        .build()) {
            CompletableFuture<SagaState> future = awaiter.awaitCompletion(
                                                                          "Demo", "k", Duration.ofSeconds(2));
            Thread.sleep(50);
            assertFalse(future.isDone(), "future stays pending until terminal");
            // Transition to terminal — awaiter detects within next poll.
            SagaState terminal = toTerminal(storage.load("Demo", "k").orElseThrow(),
                                            SagaStatus.COMPENSATED);
            storage.save(terminal, 1L);
            assertEquals(SagaStatus.COMPENSATED, future.get(2, TimeUnit.SECONDS).status());
        }
    }

    @Test
    void awaitCompletion_timesOut_whenSagaNeverTerminal() {
        InMemorySagaStorage storage = new InMemorySagaStorage();
        try (SagaCompletionAwaiter awaiter =
                SagaCompletionAwaiter.builder(storage)
                        .pollInterval(Duration.ofMillis(20))
                        .build()) {
            CompletableFuture<SagaState> future = awaiter.awaitCompletion(
                                                                          "Demo", "k", Duration.ofMillis(200));
            ExecutionException           ee     = assertThrows(ExecutionException.class,
                                                               () -> future.get(2, TimeUnit.SECONDS));
            assertTrue(ee.getCause() instanceof TimeoutException,
                       "expected TimeoutException, got " + ee.getCause());
        }
    }

    @Test
    void close_cancelsEveryPendingFuture() {
        InMemorySagaStorage          storage = new InMemorySagaStorage();
        SagaCompletionAwaiter        awaiter = SagaCompletionAwaiter.builder(storage)
                .pollInterval(Duration.ofMillis(50))
                .build();
        CompletableFuture<SagaState> a       = awaiter.awaitCompletion(
                                                                       "Demo", "saga-a", Duration.ofMinutes(1));
        CompletableFuture<SagaState> b       = awaiter.awaitCompletion(
                                                                       "Demo", "saga-b", Duration.ofMinutes(1));
        awaiter.close();
        assertTrue(a.isCompletedExceptionally());
        assertTrue(b.isCompletedExceptionally());
    }

    @Test
    void awaitCompletion_validation_rejectsNonPositiveTimeout() {
        InMemorySagaStorage storage = new InMemorySagaStorage();
        try (SagaCompletionAwaiter awaiter =
                SagaCompletionAwaiter.builder(storage).build()) {
            assertThrows(IllegalArgumentException.class,
                         () -> awaiter.awaitCompletion("Demo", "k", Duration.ZERO));
            assertThrows(IllegalArgumentException.class,
                         () -> awaiter.awaitCompletion("Demo", "k", Duration.ofMillis(-1)));
        }
    }

    @Test
    void builder_validation_rejectsNonPositivePollInterval() {
        assertThrows(IllegalArgumentException.class,
                     () -> SagaCompletionAwaiter.builder(new InMemorySagaStorage())
                             .pollInterval(Duration.ZERO));
    }
}
