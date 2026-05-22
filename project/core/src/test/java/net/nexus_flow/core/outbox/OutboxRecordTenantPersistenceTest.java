package net.nexus_flow.core.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Clock;
import java.util.List;
import net.nexus_flow.core.ddd.AbstractDomainEvent;
import net.nexus_flow.core.ddd.Aggregate;
import net.nexus_flow.core.ddd.DomainEvent;
import net.nexus_flow.core.runtime.ExecutionContext;
import net.nexus_flow.core.runtime.ids.TenantId;
import org.junit.jupiter.api.Test;

/**
 * Pins the multi-tenant persistence contract on the outbox path:
 *
 * <ol>
 * <li>{@link OutboxAppender#appendDrainedEvents} extracts {@link ExecutionContext#tenant()} and
 * stores it on every {@link OutboxRecord}.
 * <li>The persisted {@link OutboxRecord#tenantId()} survives lifecycle transitions ({@code
 *       asPending} / {@code asPublished} / {@code asRetrying} / {@code asFailedTerminal} / {@code
 *       withStatus}). Per-tenant metrics that fire on transition events (DLQ alerts, retry-rate
 * dashboards) need the tenant attached to <em>every</em> state of the row, not just the
 * initial PENDING one.
 * <li>Single-tenant / system-level dispatches (where {@code ctx.tenant() == null}) persist {@code
 *       null} — the field is genuinely optional, not a "must always set" gotcha.
 * </ol>
 */
class OutboxRecordTenantPersistenceTest {

    static final class Beat extends AbstractDomainEvent {
        @Serial
        private static final long serialVersionUID = 1L;

        Beat(String aggregateId) {
            super(aggregateId);
        }
    }

    static final class Heart extends Aggregate {
        @Serial
        private static final long serialVersionUID = 1L;

        void beat() {
            recordEvent(new Beat("heart-multi-tenant"));
        }
    }

    @Test
    void appender_persistsTenantFromContext() {
        InMemoryOutboxStorage               storage = new InMemoryOutboxStorage();
        JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
        Clock                               clock   = Clock.systemUTC();

        TenantId         acme = TenantId.of("acme");
        ExecutionContext ctx  = ExecutionContext.root().withTenant(acme);

        Heart heart = new Heart();
        heart.beat();
        List<DomainEvent> drained = heart.drainEvents();
        OutboxAppender.appendDrainedEvents(drained, ctx, storage, clock, codec);

        List<OutboxRecord> snapshot = storage.snapshot();
        assertEquals(1, snapshot.size());
        OutboxRecord row = snapshot.getFirst();
        assertEquals(acme, row.tenantId(), "appender must extract ctx.tenant() and persist it");
    }

    @Test
    void appender_storesNullTenant_whenContextHasNoTenant() {
        InMemoryOutboxStorage               storage = new InMemoryOutboxStorage();
        JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();

        Heart heart = new Heart();
        heart.beat();
        List<DomainEvent> drained = heart.drainEvents();
        OutboxAppender.appendDrainedEvents(
                                           drained, ExecutionContext.root(), storage, Clock.systemUTC(), codec);

        OutboxRecord row = storage.snapshot().getFirst();
        assertNull(row.tenantId(), "single-tenant ctx must persist tenantId=null");
    }

    @Test
    void tenantIdSurvives_allLifecycleTransitions() {
        InMemoryOutboxStorage               storage = new InMemoryOutboxStorage();
        JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
        TenantId                            acme    = TenantId.of("acme");
        ExecutionContext                    ctx     = ExecutionContext.root().withTenant(acme);
        Clock                               clock   = Clock.systemUTC();

        Heart heart = new Heart();
        heart.beat();
        OutboxAppender.appendDrainedEvents(heart.drainEvents(), ctx, storage, clock, codec);
        OutboxRecord original = storage.snapshot().getFirst();
        assertEquals(acme, original.tenantId());

        // withStatus
        OutboxRecord viaWithStatus = original.withStatus(OutboxStatus.IN_FLIGHT);
        assertEquals(acme, viaWithStatus.tenantId(), "withStatus must preserve tenantId");

        // asPending
        OutboxRecord viaPending = original.asPending(null);
        assertEquals(acme, viaPending.tenantId(), "asPending must preserve tenantId");

        // asPublished
        OutboxRecord viaPublished = original.asPublished(clock.instant());
        assertEquals(acme, viaPublished.tenantId(), "asPublished must preserve tenantId");

        // asRetrying
        OutboxRecord viaRetrying =
                original.asRetrying("boom", clock.instant(), clock.instant().plusSeconds(60));
        assertEquals(acme, viaRetrying.tenantId(), "asRetrying must preserve tenantId");

        // asFailedTerminal
        OutboxRecord viaTerminal = original.asFailedTerminal("boom", clock.instant());
        assertEquals(acme, viaTerminal.tenantId(), "asFailedTerminal must preserve tenantId");
    }

    @Test
    void releaseToReady_preservesTenantId() {
        InMemoryOutboxStorage               storage = new InMemoryOutboxStorage();
        JavaSerializationOutboxPayloadCodec codec   = new JavaSerializationOutboxPayloadCodec();
        TenantId                            acme    = TenantId.of("acme");
        ExecutionContext                    ctx     = ExecutionContext.root().withTenant(acme);
        Clock                               clock   = Clock.systemUTC();

        Heart heart = new Heart();
        heart.beat();
        OutboxAppender.appendDrainedEvents(heart.drainEvents(), ctx, storage, clock, codec);
        OutboxRecord row = storage.snapshot().getFirst();
        // Claim moves it to IN_FLIGHT
        storage.claimBatch(10, clock.instant());
        // Release back to PENDING (no attempt increment).
        storage.releaseToReady(row.outboxId());

        OutboxRecord after = storage.findById(row.outboxId());
        assertNotNull(after);
        assertEquals(OutboxStatus.PENDING, after.status());
        assertEquals(
                     acme,
                     after.tenantId(),
                     "releaseToReady must preserve tenantId — per-tenant metrics on the re-attempt depend on "
                             + "it");
    }
}
