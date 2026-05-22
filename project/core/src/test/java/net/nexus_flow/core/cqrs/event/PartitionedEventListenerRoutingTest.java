package net.nexus_flow.core.cqrs.event;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.runtime.FlowRuntime;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link PartitionedEventListener} routing contract:
 *
 * <ul>
 * <li>Single-instance default: a {@code PartitionedEventListener} with {@code partitionCount=1}
 * receives every event.
 * <li>Multi-instance sharding: N sibling instances with {@code partitionCount=N} and distinct
 * {@code partitionIndex} each receive exactly the events whose key hashes to their slot.
 * <li>Same key → same shard: every event with key {@code K} is delivered to exactly ONE instance
 * (the one owning {@code Math.floorMod(K.hashCode(), N)}).
 * <li>Registration emits an INFO log naming the listener, its partition, and the total.
 * </ul>
 *
 * <p>Replaces the previous {@code PartitionedEventListenerNotRoutedWarnsTest}: routing is now
 * implemented, so the "warn until routing lands" test is obsolete; this regression pins the actual
 * routing behaviour.
 */
class PartitionedEventListenerRoutingTest {

    static final class Pulse extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        private final String key;

        Pulse(String key) {
            super(UUID.randomUUID().toString());
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    /** Sharded listener that records every event it actually handles. */
    static final class ShardedPulseListener extends AbstractDomainEventListener<Pulse>
            implements PartitionedEventListener<Pulse> {
        private final int index;
        private final int totalCount;
        final List<Pulse> received = new CopyOnWriteArrayList<>();

        ShardedPulseListener(int index, int totalCount) {
            this.index      = index;
            this.totalCount = totalCount;
        }

        @Override
        public String partitionKey(Pulse event) {
            return event.key();
        }

        @Override
        public int partitionCount() {
            return totalCount;
        }

        @Override
        public int partitionIndex() {
            return index;
        }

        @Override
        public void handle(Pulse event) {
            received.add(event);
        }
    }

    @Test
    void singleInstance_default_receivesEveryEvent() {
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            ShardedPulseListener sole = new ShardedPulseListener(0, 1);
            runtime.events().register(sole);

            for (int i = 0; i < 10; i++) {
                runtime.events().dispatchResult(new Pulse("key-" + i));
            }
            assertEquals(
                         10, sole.received.size(), "single-instance default routes every event to that instance");
        }
    }

    @Test
    void fourWaySharding_eachEventLandsOnExactlyOneInstance() {
        int shardCount = 4;
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            ShardedPulseListener[] shards = new ShardedPulseListener[shardCount];
            for (int i = 0; i < shardCount; i++) {
                shards[i] = new ShardedPulseListener(i, shardCount);
                runtime.events().register(shards[i]);
            }

            int eventCount = 200;
            for (int i = 0; i < eventCount; i++) {
                runtime.events().dispatchResult(new Pulse("key-" + i));
            }

            int totalReceived = 0;
            for (ShardedPulseListener shard : shards) {
                totalReceived += shard.received.size();
            }
            assertEquals(
                         eventCount,
                         totalReceived,
                         "every event MUST be delivered exactly once across the shard pool");

            // Verify per-event routing determinism: each event lands on the EXPECTED shard.
            for (int i = 0; i < eventCount; i++) {
                String  key             = "key-" + i;
                int     expectedShard   = Math.floorMod(key.hashCode(), shardCount);
                boolean foundInExpected =
                        shards[expectedShard].received.stream().anyMatch(p -> p.key().equals(key));
                assertTrue(foundInExpected, "event '" + key + "' MUST land on shard " + expectedShard);
                // And NOT on any other shard.
                for (int s = 0; s < shardCount; s++) {
                    if (s == expectedShard)
                        continue;
                    boolean foundInOther = shards[s].received.stream().anyMatch(p -> p.key().equals(key));
                    assertFalse(
                                foundInOther,
                                "event '"
                                        + key
                                        + "' must NOT land on shard "
                                        + s
                                        + " (expected "
                                        + expectedShard
                                        + ")");
                }
            }
        }
    }

    @Test
    void sameKey_alwaysLandsOnSameShard() {
        int shardCount = 3;
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            ShardedPulseListener[] shards = new ShardedPulseListener[shardCount];
            for (int i = 0; i < shardCount; i++) {
                shards[i] = new ShardedPulseListener(i, shardCount);
                runtime.events().register(shards[i]);
            }

            // Dispatch the same key 100 times — must land on the same shard every time.
            for (int i = 0; i < 100; i++) {
                runtime.events().dispatchResult(new Pulse("customer-42"));
            }

            int expectedShard = Math.floorMod("customer-42".hashCode(), shardCount);
            assertEquals(
                         100,
                         shards[expectedShard].received.size(),
                         "every event for the same key MUST land on the same shard");
            for (int s = 0; s < shardCount; s++) {
                if (s == expectedShard)
                    continue;
                assertEquals(
                             0,
                             shards[s].received.size(),
                             "shard " + s + " must receive nothing — owner is shard " + expectedShard);
            }
        }
    }

    @Test
    void registration_emitsInfo_namingPartition() {
        Logger  jul    = Logger.getLogger(DefaultEventBus.class.getName());
        Level   prior  = jul.getLevel();
        Handler captor =
                new Handler() {
                                   final List<LogRecord> records = new CopyOnWriteArrayList<>();

                                   @Override
                                   public void publish(LogRecord r) {
                                       records.add(r);
                                   }

                                   @Override
                                   public void flush() {
                                   }

                                   @Override
                                   public void close() {
                                   }

                                   List<LogRecord> records() {
                                       return records;
                                   }
                               };
        jul.setLevel(Level.INFO);
        jul.addHandler(captor);
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            runtime.events().register(new ShardedPulseListener(2, 4));
            List<LogRecord> records   =
                    ((java.util.function.Supplier<List<LogRecord>>) () -> {
                                                  try {
                                                      var f = captor.getClass().getDeclaredField("records");
                                                      f.setAccessible(true);
                                                      @SuppressWarnings("unchecked") List<LogRecord> r = (List<LogRecord>) f.get(captor);
                                                      return r;
                                                  } catch (Exception e) {
                                                      throw new RuntimeException(e);
                                                  }
                                              })
                            .get();
            boolean         foundInfo =
                    records.stream()
                            .filter(r -> r.getLevel() == Level.INFO)
                            .map(LogRecord::getMessage)
                            .anyMatch(
                                      m -> m.contains("ShardedPulseListener") && m.contains("partition 2") && m.contains("of 4"));
            assertTrue(
                       foundInfo,
                       "registration of PartitionedEventListener must INFO-log the partition index + count");
        } finally {
            jul.removeHandler(captor);
            jul.setLevel(prior);
        }
    }

    @Test
    void unevenDistribution_acrossManyKeys_isStillCorrectAndComplete() {
        int shardCount = 8;
        try (FlowRuntime runtime = FlowRuntime.builder().build()) {
            ShardedPulseListener[] shards = new ShardedPulseListener[shardCount];
            for (int i = 0; i < shardCount; i++) {
                shards[i] = new ShardedPulseListener(i, shardCount);
                runtime.events().register(shards[i]);
            }

            int           eventCount = 1000;
            AtomicInteger dispatched = new AtomicInteger();
            for (int i = 0; i < eventCount; i++) {
                runtime.events().dispatchResult(new Pulse(UUID.randomUUID().toString()));
                dispatched.incrementAndGet();
            }

            int total = 0;
            for (ShardedPulseListener shard : shards)
                total += shard.received.size();
            assertEquals(
                         eventCount,
                         total,
                         "1000 random-key events MUST sum to 1000 across the 8 shards (no drops, no duplicates)");
            // Distribution sanity: every shard should receive at least 1 event (probabilistically
            // overwhelming with 1000 random UUIDs across 8 shards).
            for (ShardedPulseListener shard : shards) {
                assertFalse(
                            shard.received.isEmpty(),
                            "shard "
                                    + shard.partitionIndex()
                                    + " received 0 — distribution is heavily skewed or routing is broken");
            }
        }
    }
}
