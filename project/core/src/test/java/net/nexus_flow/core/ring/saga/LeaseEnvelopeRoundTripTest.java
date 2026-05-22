package net.nexus_flow.core.ring.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.wire.RingProtocolException;
import net.nexus_flow.core.saga.SagaId;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link LeaseRequestEnvelope} and {@link LeaseGrantEnvelope} round-trip + defensive
 * caps. These are the wire shapes for cross-pod lease coordination; drift in either would
 * silently break ownership handoff across pod death.
 */
class LeaseEnvelopeRoundTripTest {

    @Test
    void request_roundTripsAllFields() {
        LeaseRequestEnvelope original =
                new LeaseRequestEnvelope(
                        SagaId.random(),
                        PeerId.of("pod-claimer-7"),
                        System.currentTimeMillis() + 30_000);
        LeaseRequestEnvelope decoded  = LeaseRequestEnvelope.decode(original.encode());
        assertEquals(original.sagaId(), decoded.sagaId());
        assertEquals(original.requesterPeerId(), decoded.requesterPeerId());
        assertEquals(original.requestedExpiryEpochMillis(), decoded.requestedExpiryEpochMillis());
    }

    @Test
    void grant_roundTripsAllFields() {
        LeaseGrantEnvelope original =
                new LeaseGrantEnvelope(
                        SagaId.random(),
                        PeerId.of("pod-grantee-3"),
                        System.currentTimeMillis() + 30_000);
        LeaseGrantEnvelope decoded  = LeaseGrantEnvelope.decode(original.encode());
        assertEquals(original.sagaId(), decoded.sagaId());
        assertEquals(original.grantedToPeerId(), decoded.grantedToPeerId());
        assertEquals(original.leaseExpiryEpochMillis(), decoded.leaseExpiryEpochMillis());
    }

    @Test
    void request_zeroOrNegativeExpiry_isRejectedAtConstruction() {
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new LeaseRequestEnvelope(SagaId.random(), PeerId.of("p"), 0L));
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new LeaseRequestEnvelope(SagaId.random(), PeerId.of("p"), -1L));
    }

    @Test
    void grant_zeroOrNegativeExpiry_isRejectedAtConstruction() {
        assertThrows(
                     IllegalArgumentException.class,
                     () -> new LeaseGrantEnvelope(SagaId.random(), PeerId.of("p"), 0L));
    }

    @Test
    void request_decode_truncated_throws() {
        byte[] valid     =
                new LeaseRequestEnvelope(SagaId.random(), PeerId.of("p"), 1L).encode();
        byte[] truncated = Arrays.copyOfRange(valid, 0, valid.length - 1);
        assertThrows(RingProtocolException.class, () -> LeaseRequestEnvelope.decode(truncated));
    }

    @Test
    void request_decode_trailingBytes_throws() {
        byte[] valid        =
                new LeaseRequestEnvelope(SagaId.random(), PeerId.of("p"), 1L).encode();
        byte[] withTrailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(
                     RingProtocolException.class, () -> LeaseRequestEnvelope.decode(withTrailing));
    }

    @Test
    void grant_decode_trailingBytes_throws() {
        byte[] valid        = new LeaseGrantEnvelope(SagaId.random(), PeerId.of("p"), 1L).encode();
        byte[] withTrailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(RingProtocolException.class, () -> LeaseGrantEnvelope.decode(withTrailing));
    }
}
