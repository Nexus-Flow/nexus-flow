package net.nexus_flow.core.ring.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.nexus_flow.core.ring.transport.PeerId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class PendingResponseRegistryTest {

    private static final PeerId      PEER = PeerId.of("peer-a");
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void register_completeWithResponse_unblocksTheFuture() throws Exception {
        try (PendingResponseRegistry reg = new PendingResponseRegistry(16, scheduler)) {
            DispatchCorrelationId                       id   = DispatchCorrelationId.next();
            CompletableFuture<DispatchResponseEnvelope> f    =
                    reg.register(id, PEER, Duration.ofSeconds(5));
            DispatchResponseEnvelope                    resp =
                    DispatchResponseEnvelope.success(id, "t", "c", new byte[0]);
            assertTrue(reg.complete(id, resp));
            assertEquals(resp, f.get(2, TimeUnit.SECONDS));
            assertEquals(0, reg.inFlight());
        }
    }

    @Test
    void registerThenTimeout_failsTheFutureWithTimeoutException_andRemovesEntry() {
        try (PendingResponseRegistry reg = new PendingResponseRegistry(16, scheduler)) {
            DispatchCorrelationId                       id    = DispatchCorrelationId.next();
            CompletableFuture<DispatchResponseEnvelope> f     =
                    reg.register(id, PEER, Duration.ofMillis(100));
            Throwable                                   cause =
                    assertThrows(java.util.concurrent.ExecutionException.class,
                                 () -> f.get(2, TimeUnit.SECONDS))
                            .getCause();
            assertTrue(cause instanceof TimeoutException, "unexpected cause: " + cause);
            assertEquals(0, reg.inFlight(), "timeout must remove the entry");
        }
    }

    @Test
    void completeAfterTimeout_returnsFalse_andDoesNotResurrectEntry() throws Exception {
        try (PendingResponseRegistry reg = new PendingResponseRegistry(16, scheduler)) {
            DispatchCorrelationId                       id = DispatchCorrelationId.next();
            CompletableFuture<DispatchResponseEnvelope> f  =
                    reg.register(id, PEER, Duration.ofMillis(50));
            Thread.sleep(200);
            assertTrue(f.isCompletedExceptionally());
            assertFalse(
                        reg.complete(id, DispatchResponseEnvelope.success(id, "t", "c", new byte[0])),
                        "complete() after timeout must return false");
            assertEquals(0, reg.inFlight());
        }
    }

    @Test
    void duplicateRegister_throws() {
        try (PendingResponseRegistry reg = new PendingResponseRegistry(16, scheduler)) {
            DispatchCorrelationId id = DispatchCorrelationId.next();
            reg.register(id, PEER, Duration.ofSeconds(5));
            assertThrows(IllegalStateException.class,
                         () -> reg.register(id, PEER, Duration.ofSeconds(5)));
        }
    }

    @Test
    void completeUnknownId_returnsFalse() {
        try (PendingResponseRegistry reg = new PendingResponseRegistry(16, scheduler)) {
            DispatchCorrelationId id = DispatchCorrelationId.next();
            assertFalse(reg.complete(id,
                                     DispatchResponseEnvelope.success(id, "t", "c", new byte[0])));
        }
    }

    @Test
    void capacityExceeded_throwsOnRegister() {
        try (PendingResponseRegistry reg = new PendingResponseRegistry(2, scheduler)) {
            reg.register(DispatchCorrelationId.next(), PEER, Duration.ofSeconds(30));
            reg.register(DispatchCorrelationId.next(), PEER, Duration.ofSeconds(30));
            assertThrows(IllegalStateException.class,
                         () -> reg.register(DispatchCorrelationId.next(), PEER, Duration.ofSeconds(30)));
        }
    }

    @Test
    void completeExceptionally_failsFutureWithGivenCause() throws Exception {
        try (PendingResponseRegistry reg = new PendingResponseRegistry(16, scheduler)) {
            DispatchCorrelationId                       id   = DispatchCorrelationId.next();
            CompletableFuture<DispatchResponseEnvelope> f    =
                    reg.register(id, PEER, Duration.ofSeconds(5));
            RuntimeException                            boom = new RuntimeException("peer dropped");
            assertTrue(reg.completeExceptionally(id, boom));
            Throwable cause =
                    assertThrows(java.util.concurrent.ExecutionException.class,
                                 () -> f.get(2, TimeUnit.SECONDS))
                            .getCause();
            assertEquals(boom, cause);
        }
    }

    @Test
    void close_cancelsAllPendingFutures() {
        PendingResponseRegistry                     reg = new PendingResponseRegistry(16, scheduler);
        DispatchCorrelationId                       a   = DispatchCorrelationId.next();
        DispatchCorrelationId                       b   = DispatchCorrelationId.next();
        CompletableFuture<DispatchResponseEnvelope> fa  = reg.register(a, PEER, Duration.ofMinutes(1));
        CompletableFuture<DispatchResponseEnvelope> fb  = reg.register(b, PEER, Duration.ofMinutes(1));
        reg.close();
        assertEquals(0, reg.inFlight());
        assertThrows(CancellationException.class, () -> fa.get(2, TimeUnit.SECONDS));
        assertThrows(CancellationException.class, () -> fb.get(2, TimeUnit.SECONDS));
    }

    @Test
    void registerNullOrNonPositiveTimeout_throws() {
        try (PendingResponseRegistry reg = new PendingResponseRegistry(16, scheduler)) {
            assertThrows(NullPointerException.class,
                         () -> reg.register(null, PEER, Duration.ofSeconds(1)));
            assertThrows(NullPointerException.class,
                         () -> reg.register(DispatchCorrelationId.next(), PEER, null));
            assertThrows(IllegalArgumentException.class,
                         () -> reg.register(DispatchCorrelationId.next(), PEER, Duration.ZERO));
            assertThrows(IllegalArgumentException.class,
                         () -> reg.register(DispatchCorrelationId.next(), PEER, Duration.ofMillis(-1)));
        }
    }

    @Test
    void inFlight_tracksRegistrationsAccurately() {
        try (PendingResponseRegistry reg = new PendingResponseRegistry(16, scheduler)) {
            assertEquals(0, reg.inFlight());
            DispatchCorrelationId a = DispatchCorrelationId.next();
            DispatchCorrelationId b = DispatchCorrelationId.next();
            reg.register(a, PEER, Duration.ofMinutes(1));
            assertEquals(1, reg.inFlight());
            reg.register(b, PEER, Duration.ofMinutes(1));
            assertEquals(2, reg.inFlight());
            reg.complete(a, DispatchResponseEnvelope.success(a, "t", "c", new byte[0]));
            assertEquals(1, reg.inFlight());
        }
    }

    @Test
    void cancelAllForPeer_failsBoundFuturesImmediately() {
        try (PendingResponseRegistry reg = new PendingResponseRegistry(16, scheduler)) {
            PeerId                                      other     = PeerId.of("peer-b");
            DispatchCorrelationId                       a         = DispatchCorrelationId.next();
            DispatchCorrelationId                       b         = DispatchCorrelationId.next();
            DispatchCorrelationId                       c         = DispatchCorrelationId.next();
            CompletableFuture<DispatchResponseEnvelope> fa        = reg.register(a, PEER, Duration.ofMinutes(1));
            CompletableFuture<DispatchResponseEnvelope> fb        = reg.register(b, PEER, Duration.ofMinutes(1));
            CompletableFuture<DispatchResponseEnvelope> fc        = reg.register(c, other, Duration.ofMinutes(1));
            int                                         cancelled = reg.cancelAllForPeer(PEER, new RuntimeException("peer closed"));
            assertEquals(2, cancelled);
            assertTrue(fa.isCompletedExceptionally());
            assertTrue(fb.isCompletedExceptionally());
            assertFalse(fc.isDone(), "futures bound to other peer must not be cancelled");
            assertEquals(1, reg.inFlight());
            assertEquals(0, reg.inFlightFor(PEER));
        }
    }
}
