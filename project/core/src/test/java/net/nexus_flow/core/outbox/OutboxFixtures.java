package net.nexus_flow.core.outbox;

import java.time.Instant;
import net.nexus_flow.core.runtime.ids.CausationId;
import net.nexus_flow.core.runtime.ids.CorrelationId;
import net.nexus_flow.core.runtime.ids.MessageId;
import net.nexus_flow.core.runtime.ids.TraceId;

/**
 * test-only fixtures for hand-crafted {@link OutboxRecord} instances. Production code goes through
 * {@link OutboxAppender}.
 */
final class OutboxFixtures {

    private OutboxFixtures() {
    }

    static OutboxRecord pending(String aggregateId, long seq, Instant recordedAt) {
        return pending(aggregateId, seq, recordedAt, null);
    }

    static OutboxRecord pending(
            String aggregateId, long seq, Instant recordedAt, Instant nextRetryAt) {
        return new OutboxRecord(
                OutboxId.next(),
                IdempotencyKey.of(aggregateId + ":" + seq),
                "test.Event",
                aggregateId,
                seq,
                TraceId.random(),
                CorrelationId.random(),
                CausationId.ROOT,
                MessageId.random(),
                Object.class,
                new byte[0],
                recordedAt,
                OutboxStatus.PENDING,
                0,
                null,
                null,
                nextRetryAt,
                null);
    }

    static OutboxRecord published(String aggregateId, long seq, Instant recordedAt) {
        return pending(aggregateId, seq, recordedAt).withStatus(OutboxStatus.PUBLISHED);
    }
}
