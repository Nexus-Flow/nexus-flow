package net.nexus_flow.core.ring.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.wire.RingProtocolException;
import net.nexus_flow.core.saga.SagaId;
import org.junit.jupiter.api.Test;

/** Pins {@link SagaStateEnvelope} round-trip + defensive caps. */
class SagaStateEnvelopeTest {

    @Test
    void roundTrip_preservesEveryField() {
        SagaStateEnvelope original =
                new SagaStateEnvelope(
                        SagaId.random(),
                        PeerId.of("pod-owner-7"),
                        System.currentTimeMillis() + 30_000,
                        42L);
        SagaStateEnvelope decoded  = SagaStateEnvelope.decode(original.encode());
        assertEquals(original.sagaId(), decoded.sagaId());
        assertEquals(original.ownerPeerId(), decoded.ownerPeerId());
        assertEquals(original.leaseExpiryEpochMillis(), decoded.leaseExpiryEpochMillis());
        assertEquals(original.stateVersion(), decoded.stateVersion());
    }

    @Test
    void zeroExpiry_rejected() {
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new SagaStateEnvelope(SagaId.random(), PeerId.of("p"), 0L, 1L));
    }

    @Test
    void negativeStateVersion_rejected() {
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new SagaStateEnvelope(SagaId.random(), PeerId.of("p"), 1L, -1L));
    }

    @Test
    void truncatedBody_throws() {
        byte[] valid     = new SagaStateEnvelope(SagaId.random(), PeerId.of("p"), 1L, 1L).encode();
        byte[] truncated = Arrays.copyOfRange(valid, 0, valid.length - 1);
        assertThrows(RingProtocolException.class, () -> SagaStateEnvelope.decode(truncated));
    }

    @Test
    void trailingBytes_throws() {
        byte[] valid        = new SagaStateEnvelope(SagaId.random(), PeerId.of("p"), 1L, 1L).encode();
        byte[] withTrailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(RingProtocolException.class, () -> SagaStateEnvelope.decode(withTrailing));
    }
}
