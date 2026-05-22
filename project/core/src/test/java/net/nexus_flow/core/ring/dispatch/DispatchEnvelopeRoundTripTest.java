package net.nexus_flow.core.ring.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.UUID;
import net.nexus_flow.core.ring.registry.HandlerRole;
import net.nexus_flow.core.ring.transport.PeerId;
import net.nexus_flow.core.ring.wire.ProtocolErrorCode;
import net.nexus_flow.core.ring.wire.RingProtocolException;
import org.junit.jupiter.api.Test;

class DispatchEnvelopeRoundTripTest {

    private static DispatchRequestEnvelope sampleRequest(HandlerRole role) {
        return new DispatchRequestEnvelope(
                role,
                DispatchCorrelationId.next(),
                PeerId.of("pod-source"),
                "com.acme.PlaceOrder",
                "java-v1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "acme",
                System.currentTimeMillis(),
                5_000L,
                new byte[]{1, 2, 3, 4, 5});
    }

    @Test
    void request_commandRole_roundTripsAllFields() {
        DispatchRequestEnvelope original = sampleRequest(HandlerRole.COMMAND);
        DispatchRequestEnvelope decoded  = DispatchRequestEnvelope.decode(original.encode());
        assertEquals(original.role(), decoded.role());
        assertEquals(original.correlationId(), decoded.correlationId());
        assertEquals(original.sourcePeerId(), decoded.sourcePeerId());
        assertEquals(original.payloadType(), decoded.payloadType());
        assertEquals(original.codecId(), decoded.codecId());
        assertEquals(original.traceId(), decoded.traceId());
        assertEquals(original.contextCorrelationId(), decoded.contextCorrelationId());
        assertEquals(original.causationId(), decoded.causationId());
        assertEquals(original.tenantId(), decoded.tenantId());
        assertEquals(original.sendInstantEpochMillis(), decoded.sendInstantEpochMillis());
        assertEquals(original.deadlineRemainingMillis(), decoded.deadlineRemainingMillis());
        assertTrue(Arrays.equals(original.payloadBytes(), decoded.payloadBytes()));
    }

    @Test
    void request_queryRole_roundTrips() {
        DispatchRequestEnvelope original = sampleRequest(HandlerRole.QUERY);
        DispatchRequestEnvelope decoded  = DispatchRequestEnvelope.decode(original.encode());
        assertEquals(HandlerRole.QUERY, decoded.role());
    }

    @Test
    void request_nullTenant_roundTripsAsNull() {
        DispatchRequestEnvelope original = new DispatchRequestEnvelope(
                HandlerRole.COMMAND,
                DispatchCorrelationId.next(),
                PeerId.of("p"), "t.X", "c",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, 0L, 0L, new byte[0]);
        DispatchRequestEnvelope decoded  = DispatchRequestEnvelope.decode(original.encode());
        assertNull(decoded.tenantId());
        assertTrue(decoded.hasNoDeadline());
    }

    @Test
    void request_negativeDeadline_isRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                     () -> new DispatchRequestEnvelope(
                             HandlerRole.COMMAND,
                             DispatchCorrelationId.next(),
                             PeerId.of("p"), "t.X", "c",
                             UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                             null, 0L, -1L, new byte[0]));
    }

    @Test
    void request_decode_truncatedPayload_throws() {
        byte[] valid     = sampleRequest(HandlerRole.COMMAND).encode();
        byte[] truncated = Arrays.copyOfRange(valid, 0, valid.length - 1);
        assertThrows(RingProtocolException.class, () -> DispatchRequestEnvelope.decode(truncated));
    }

    @Test
    void request_decode_unknownRoleCode_throws() {
        byte[] valid = sampleRequest(HandlerRole.COMMAND).encode();
        valid[0] = (byte) 0xFE;
        assertThrows(RingProtocolException.class, () -> DispatchRequestEnvelope.decode(valid));
    }

    @Test
    void response_success_roundTripsBody() {
        DispatchCorrelationId    id       = DispatchCorrelationId.next();
        DispatchResponseEnvelope original = DispatchResponseEnvelope.success(
                                                                             id, "com.acme.OrderId", "java-v1", new byte[]{42});
        DispatchResponseEnvelope decoded  = DispatchResponseEnvelope.decode(original.encode());
        assertEquals(id, decoded.correlationId());
        assertEquals(DispatchResponseEnvelope.Outcome.SUCCESS, decoded.outcome());
        assertEquals(ProtocolErrorCode.OK, decoded.errorCode());
        assertEquals("com.acme.OrderId", decoded.payloadType());
        assertEquals(1, decoded.payloadBytes().length);
        assertEquals(42, decoded.payloadBytes()[0]);
    }

    @Test
    void response_failure_carriesProtocolErrorCode_andSanitisedReason() {
        DispatchCorrelationId    id       = DispatchCorrelationId.next();
        DispatchResponseEnvelope original = DispatchResponseEnvelope.failure(
                                                                             id, ProtocolErrorCode.DOMAIN_FAILURE, "domain rule violated");
        DispatchResponseEnvelope decoded  = DispatchResponseEnvelope.decode(original.encode());
        assertEquals(DispatchResponseEnvelope.Outcome.FAILURE, decoded.outcome());
        assertEquals(ProtocolErrorCode.DOMAIN_FAILURE, decoded.errorCode());
        assertEquals("domain rule violated", decoded.reason());
        assertEquals(0, decoded.payloadBytes().length);
    }

    @Test
    void response_notFound_andTimeout_areDistinctFromFailure() {
        DispatchCorrelationId    id    = DispatchCorrelationId.next();
        DispatchResponseEnvelope nf    = DispatchResponseEnvelope.notFound(id, "directory stale");
        DispatchResponseEnvelope to    = DispatchResponseEnvelope.timeout(id, "handler 5s");
        DispatchResponseEnvelope nfDec = DispatchResponseEnvelope.decode(nf.encode());
        DispatchResponseEnvelope toDec = DispatchResponseEnvelope.decode(to.encode());
        assertEquals(DispatchResponseEnvelope.Outcome.NOT_FOUND, nfDec.outcome());
        assertEquals(ProtocolErrorCode.NOT_FOUND, nfDec.errorCode());
        assertEquals(DispatchResponseEnvelope.Outcome.TIMEOUT, toDec.outcome());
        assertEquals(ProtocolErrorCode.DEADLINE_EXCEEDED, toDec.errorCode());
    }

    @Test
    void response_unknownOutcomeCode_throwsOnDecode() {
        byte[] valid = DispatchResponseEnvelope.success(
                                                        DispatchCorrelationId.next(), "t", "c", new byte[0]).encode();
        valid[16] = (byte) 0xFE;
        assertThrows(RingProtocolException.class, () -> DispatchResponseEnvelope.decode(valid));
    }

    @Test
    void response_unknownErrorCode_decodesAsUnknown_notThrow() {
        byte[] valid = DispatchResponseEnvelope.success(
                                                        DispatchCorrelationId.next(), "t", "c", new byte[0]).encode();
        // overwrite errorCode discriminator (position 17: after 16-byte UUID + outcome byte)
        valid[17] = (byte) 0xAB;
        DispatchResponseEnvelope decoded = DispatchResponseEnvelope.decode(valid);
        assertEquals(ProtocolErrorCode.UNKNOWN, decoded.errorCode());
    }

    @Test
    void response_forbiddenAndUnavailable_outcomesAndCodesAreDistinct() {
        DispatchCorrelationId    id        = DispatchCorrelationId.next();
        DispatchResponseEnvelope forbidden = DispatchResponseEnvelope.forbidden(id, "denied");
        DispatchResponseEnvelope unavail   = DispatchResponseEnvelope.unavailable(id, "draining");
        DispatchResponseEnvelope fDec      = DispatchResponseEnvelope.decode(forbidden.encode());
        DispatchResponseEnvelope uDec      = DispatchResponseEnvelope.decode(unavail.encode());
        assertEquals(DispatchResponseEnvelope.Outcome.FORBIDDEN, fDec.outcome());
        assertEquals(ProtocolErrorCode.FORBIDDEN, fDec.errorCode());
        assertEquals(DispatchResponseEnvelope.Outcome.UNAVAILABLE, uDec.outcome());
        assertEquals(ProtocolErrorCode.UNAVAILABLE, uDec.errorCode());
    }

    @Test
    void outcomeWireCodes_areFrozen_pinsThePublishedFormat() {
        assertEquals((byte) 0x01, DispatchResponseEnvelope.Outcome.SUCCESS.wireCode());
        assertEquals((byte) 0x02, DispatchResponseEnvelope.Outcome.FAILURE.wireCode());
        assertEquals((byte) 0x03, DispatchResponseEnvelope.Outcome.PARTIAL_FAILURE.wireCode());
        assertEquals((byte) 0x04, DispatchResponseEnvelope.Outcome.ACCEPTED.wireCode());
        assertEquals((byte) 0x10, DispatchResponseEnvelope.Outcome.NOT_FOUND.wireCode());
        assertEquals((byte) 0x11, DispatchResponseEnvelope.Outcome.TIMEOUT.wireCode());
        assertEquals((byte) 0x12, DispatchResponseEnvelope.Outcome.FORBIDDEN.wireCode());
        assertEquals((byte) 0x13, DispatchResponseEnvelope.Outcome.UNAVAILABLE.wireCode());
    }
}
