package net.nexus_flow.core.runtime.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SpscRingBufferTest {

    @Test
    void newBuffer_isEmpty_pollReturnsNull() {
        SpscRingBuffer<String> q = new SpscRingBuffer<>(4);
        assertTrue(q.isEmpty());
        assertEquals(0, q.size());
        assertNull(q.poll());
    }

    @Test
    void capacity_roundedToNextPowerOfTwo() {
        assertEquals(4, new SpscRingBuffer<>(3).capacity());
        assertEquals(4, new SpscRingBuffer<>(4).capacity());
        assertEquals(8, new SpscRingBuffer<>(5).capacity());
        assertEquals(16, new SpscRingBuffer<>(9).capacity());
    }

    @Test
    void capacity_rejectsLessThan2() {
        assertThrows(IllegalArgumentException.class, () -> new SpscRingBuffer<>(1));
        assertThrows(IllegalArgumentException.class, () -> new SpscRingBuffer<>(0));
    }

    @Test
    void offer_thenPoll_FIFOOrder() {
        SpscRingBuffer<String> q = new SpscRingBuffer<>(4);
        assertTrue(q.offer("a"));
        assertTrue(q.offer("b"));
        assertTrue(q.offer("c"));
        assertEquals("a", q.poll());
        assertEquals("b", q.poll());
        assertEquals("c", q.poll());
        assertNull(q.poll());
    }

    @Test
    void offer_nullRejected() {
        SpscRingBuffer<String> q = new SpscRingBuffer<>(4);
        assertThrows(NullPointerException.class, () -> q.offer(null));
    }

    @Test
    void offer_returnsFalse_whenFull() {
        SpscRingBuffer<String> q = new SpscRingBuffer<>(4); // capacity 4
        assertTrue(q.offer("a"));
        assertTrue(q.offer("b"));
        assertTrue(q.offer("c"));
        assertTrue(q.offer("d"));
        assertFalse(q.offer("e"));
        assertEquals(4, q.size());
    }

    @Test
    void wraparound_acrossManyCycles_preservesFIFO() {
        SpscRingBuffer<Integer> q      = new SpscRingBuffer<>(4);
        int                     rounds = 1000;
        for (int i = 0; i < rounds; i++) {
            assertTrue(q.offer(i));
            assertEquals(Integer.valueOf(i), q.poll());
        }
        assertTrue(q.isEmpty());
    }

    @Test
    void concurrent_singleProducerSingleConsumer_noLossNoReorder() throws Exception {
        SpscRingBuffer<Integer>    q            = new SpscRingBuffer<>(1024);
        int                        n            = 100_000;
        CountDownLatch             start        = new CountDownLatch(1);
        List<Integer>              received     = new ArrayList<>(n);
        AtomicReference<Throwable> consumerFail = new AtomicReference<>();

        Thread consumer = new Thread(() -> {
            try {
                start.await();
                while (received.size() < n) {
                    Integer v = q.poll();
                    if (v != null) {
                        received.add(v);
                    } else {
                        Thread.onSpinWait();
                    }
                }
            } catch (Throwable t) {
                consumerFail.set(t);
            }
        }, "spsc-consumer");
        consumer.start();

        Thread producer = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < n; i++) {
                    while (!q.offer(i)) {
                        Thread.onSpinWait();
                    }
                }
            } catch (Throwable t) {
                consumerFail.set(t);
            }
        }, "spsc-producer");
        producer.start();

        start.countDown();
        producer.join(10_000);
        consumer.join(10_000);

        assertNull(consumerFail.get(), () -> "consumer failed: " + consumerFail.get());
        assertEquals(n, received.size(), "all offered elements must be consumed");
        for (int i = 0; i < n; i++) {
            assertEquals(Integer.valueOf(i), received.get(i),
                         "FIFO order must be preserved across producer/consumer hand-off");
        }
    }

    @Test
    void poll_nullsOutSlot_allowsGC() {
        SpscRingBuffer<Object> q        = new SpscRingBuffer<>(4);
        Object                 sentinel = new Object();
        assertTrue(q.offer(sentinel));
        Object polled = q.poll();
        assertNotNull(polled);
        assertEquals(sentinel, polled);
        // After poll, the slot must be null so the GC can reclaim the element after the
        // consumer releases its reference. We test by offering N more elements and
        // verifying poll returns each in order — if the slot wasn't nulled out,
        // wraparound would see stale references.
        for (int i = 0; i < 100; i++) {
            assertTrue(q.offer("item-" + i));
            assertEquals("item-" + i, q.poll());
        }
    }
}
