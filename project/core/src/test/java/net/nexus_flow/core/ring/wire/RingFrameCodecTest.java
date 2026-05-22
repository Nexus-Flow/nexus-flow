package net.nexus_flow.core.ring.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Contract tests for {@link RingFrameCodec}. Every shipped implementation
 * ({@link RingFrameCodec#BYTE_BUFFER}, {@link RingFrameCodec#MEMORY_SEGMENT}) and any
 * future adapter-provided codec must pass the same suite to remain wire-format-compatible
 * — the protocol reference encoding is the only legal output.
 */
class RingFrameCodecTest {

    private final RingFrameCodec codec = RingFrameCodec.BYTE_BUFFER;

    static Stream<RingFrameCodec> allCodecs() {
        return Stream.of(RingFrameCodec.BYTE_BUFFER, RingFrameCodec.MEMORY_SEGMENT);
    }

    @Test
    void encodeProducesProtocolReferenceBytes() {
        byte[]    body  = "payload".getBytes();
        RingFrame frame = new RingFrame(FrameType.EVENT, FrameBody.ofOwned(body));

        byte[] wire = codec.encode(frame, 1024);

        assertEquals(RingProtocol.HEADER_BYTES + body.length, wire.length);

        ByteBuffer parsed = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        byte[]     magic  = new byte[RingProtocol.MAGIC_BYTES];
        parsed.get(magic);
        assertArrayEquals(RingProtocol.MAGIC, magic);
        assertEquals(RingProtocol.VERSION, parsed.get());
        assertEquals(FrameType.EVENT.wireCode(), parsed.get());
        assertEquals((short) 0, parsed.getShort());
        assertEquals(body.length, parsed.getInt());

        byte[] bodyOut = new byte[body.length];
        parsed.get(bodyOut);
        assertArrayEquals(body, bodyOut);
    }

    @Test
    void encodeIntoMatchesEncodeBytes() {
        byte[]    body  = "abc".getBytes();
        RingFrame frame = new RingFrame(FrameType.EVENT, FrameBody.ofOwned(body));

        byte[]     full    = codec.encode(frame, 1024);
        ByteBuffer scratch = ByteBuffer.allocate(64);
        codec.encodeInto(frame, scratch, 1024);
        scratch.flip();

        byte[] copied = new byte[scratch.remaining()];
        scratch.get(copied);
        assertArrayEquals(full, copied);
    }

    @Test
    void encodedLengthMatchesActualEncoding() {
        RingFrame frame = new RingFrame(FrameType.EVENT, FrameBody.ofOwned("x".getBytes()));
        assertEquals(codec.encode(frame, 1024).length, codec.encodedLength(frame));
    }

    @Test
    void encodeRejectsBodyAboveCap() {
        byte[]    body  = new byte[1025];
        RingFrame frame = new RingFrame(FrameType.EVENT, FrameBody.ofOwned(body));
        assertThrows(FrameTooLargeException.class, () -> codec.encode(frame, 1024));
    }

    @Test
    void newDecoderRoundtripsEncodedFrame() {
        byte[]    body  = "hola".getBytes();
        RingFrame frame = new RingFrame(FrameType.EVENT, FrameBody.ofOwned(body));
        byte[]    wire  = codec.encode(frame, 1024);

        FrameDecoder    decoder = codec.newDecoder(1024);
        List<RingFrame> decoded = new ArrayList<>();
        decoder.tryDecode(ByteBuffer.wrap(wire), decoded::add);

        assertEquals(1, decoded.size());
        assertEquals(FrameType.EVENT, decoded.getFirst().type());
        assertArrayEquals(body, decoded.getFirst().body().copyToByteArray());
    }

    @Test
    void defaultCodecIsByteBufferSingleton() {
        assertSame(DefaultRingFrameCodec.INSTANCE, RingFrameCodec.BYTE_BUFFER);
    }

    @Test
    void memorySegmentCodecIsSingleton() {
        assertSame(MemorySegmentRingFrameCodec.INSTANCE, RingFrameCodec.MEMORY_SEGMENT);
    }

    @ParameterizedTest
    @MethodSource("allCodecs")
    void everyCodecProducesByteIdenticalEncoding(RingFrameCodec other) {
        byte[]    body  = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        RingFrame frame = new RingFrame(FrameType.EVENT, FrameBody.ofOwned(body));

        byte[] reference = RingFrameCodec.BYTE_BUFFER.encode(frame, 1024);
        byte[] candidate = other.encode(frame, 1024);
        assertArrayEquals(reference, candidate,
                          "codec " + other.getClass().getSimpleName() + " disagrees with protocol reference");
    }

    @ParameterizedTest
    @MethodSource("allCodecs")
    void everyCodecRoundtripsLargeBody(RingFrameCodec other) {
        byte[] body = new byte[4096];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i & 0xFF);
        }
        RingFrame frame = new RingFrame(FrameType.EVENT, FrameBody.ofOwned(body));
        byte[]    wire  = other.encode(frame, 1 << 20);

        FrameDecoder    decoder = other.newDecoder(1 << 20);
        List<RingFrame> decoded = new ArrayList<>();
        decoder.tryDecode(ByteBuffer.wrap(wire), decoded::add);

        assertEquals(1, decoded.size());
        assertArrayEquals(body, decoded.getFirst().body().copyToByteArray());
    }
}
