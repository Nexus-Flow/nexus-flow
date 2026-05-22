package net.nexus_flow.core.ring.wire;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link FrameDecoder} state machine against the 15 framing edge cases the
 * {@code nexus-java-network-io-lowlevel} skill enumerates (section 21, items 1-11) plus the
 * ring-protocol-specific invariants (magic, version, type code).
 */
class FrameDecoderTest {

    private final FrameDecoder    decoder = new FrameDecoder();
    private final List<RingFrame> emitted = new ArrayList<>();

    private void feed(byte[] bytes) {
        decoder.tryDecode(ByteBuffer.wrap(bytes), emitted::add);
    }

    // ---- Happy-path framing ----------------------------------------------------

    @Test
    void fullFrameInOneRead_decodesSingleFrame() {
        byte[] encoded =
                FrameEncoder.encode(
                                    new RingFrame(FrameType.PING, "hi".getBytes(StandardCharsets.UTF_8)));
        feed(encoded);
        assertEquals(1, emitted.size());
        assertEquals(FrameType.PING, emitted.getFirst().type());
        assertEquals("hi", new String(emitted.getFirst().bodyBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void emptyBodyFrame_decodesWithZeroLengthBody() {
        feed(FrameEncoder.encode(RingFrame.ofType(FrameType.PONG)));
        assertEquals(1, emitted.size());
        assertEquals(FrameType.PONG, emitted.getFirst().type());
        assertEquals(0, emitted.getFirst().body().length());
    }

    @Test
    void multipleFramesInOneRead_decodesAllInOrder() {
        byte[] f1     = FrameEncoder.encode(new RingFrame(FrameType.PING, new byte[]{1}));
        byte[] f2     = FrameEncoder.encode(new RingFrame(FrameType.PONG, new byte[]{2}));
        byte[] f3     = FrameEncoder.encode(new RingFrame(FrameType.EVENT, new byte[]{3, 4, 5}));
        byte[] concat = new byte[f1.length + f2.length + f3.length];
        System.arraycopy(f1, 0, concat, 0, f1.length);
        System.arraycopy(f2, 0, concat, f1.length, f2.length);
        System.arraycopy(f3, 0, concat, f1.length + f2.length, f3.length);
        feed(concat);
        assertEquals(3, emitted.size());
        assertEquals(FrameType.PING, emitted.get(0).type());
        assertEquals(FrameType.PONG, emitted.get(1).type());
        assertEquals(FrameType.EVENT, emitted.get(2).type());
        assertEquals(3, emitted.get(2).body().length());
    }

    // ---- Partial-read scenarios ------------------------------------------------

    @Test
    void headerSplitAcrossReads_decoderResumesAndEmits() {
        byte[] encoded = FrameEncoder.encode(new RingFrame(FrameType.PING, new byte[]{42}));
        // Feed first 6 bytes, then the rest.
        feed(Arrays.copyOfRange(encoded, 0, 6));
        assertEquals(0, emitted.size(), "partial header MUST NOT emit a frame");
        feed(Arrays.copyOfRange(encoded, 6, encoded.length));
        assertEquals(1, emitted.size());
        assertEquals(FrameType.PING, emitted.getFirst().type());
        assertEquals(42, emitted.getFirst().bodyBytes()[0]);
    }

    @Test
    void headerSplitByteByByte_decoderStillEmits() {
        byte[] encoded = FrameEncoder.encode(new RingFrame(FrameType.PING, new byte[]{99}));
        for (byte b : encoded) {
            feed(new byte[]{b});
        }
        assertEquals(1, emitted.size());
        assertEquals(99, emitted.getFirst().bodyBytes()[0]);
    }

    @Test
    void bodySplitAcrossReads_decoderResumesAndEmits() {
        byte[] body = new byte[1024];
        for (int i = 0; i < body.length; i++)
            body[i] = (byte) (i & 0xFF);
        byte[] encoded = FrameEncoder.encode(new RingFrame(FrameType.EVENT, body));
        feed(Arrays.copyOfRange(encoded, 0, RingProtocol.HEADER_BYTES + 100));
        assertEquals(0, emitted.size(), "partial body MUST NOT emit a frame");
        feed(Arrays.copyOfRange(encoded, RingProtocol.HEADER_BYTES + 100, encoded.length));
        assertEquals(1, emitted.size());
        assertTrue(Arrays.equals(body, emitted.getFirst().bodyBytes()));
    }

    @Test
    void interleavedHeaderAndBodySplits_handledCorrectly() {
        byte[] encoded = FrameEncoder.encode(new RingFrame(FrameType.EVENT, new byte[]{1, 2, 3, 4, 5}));
        feed(Arrays.copyOfRange(encoded, 0, 4)); // partial header
        feed(Arrays.copyOfRange(encoded, 4, 12)); // rest of header
        feed(Arrays.copyOfRange(encoded, 12, 14)); // first 2 body bytes
        assertEquals(0, emitted.size());
        feed(Arrays.copyOfRange(encoded, 14, encoded.length)); // rest of body
        assertEquals(1, emitted.size());
        assertTrue(Arrays.equals(new byte[]{1, 2, 3, 4, 5}, emitted.getFirst().bodyBytes()));
    }

    // ---- Protocol violations ---------------------------------------------------

    @Test
    void badMagic_throwsRingProtocolException() {
        byte[] bad = new byte[RingProtocol.HEADER_BYTES];
        bad[0] = 'X';
        bad[1] = 'Y';
        bad[2] = 'Z';
        bad[3] = 'Q';
        RingProtocolException ex =
                assertThrows(RingProtocolException.class, () -> feed(bad));
        assertTrue(ex.getMessage().contains("bad magic"), ex.getMessage());
    }

    @Test
    void wrongVersion_throwsRingProtocolException() {
        ByteBuffer buf = ByteBuffer.allocate(RingProtocol.HEADER_BYTES);
        buf.put(RingProtocol.MAGIC);
        buf.put((byte) 99); // unsupported version
        buf.put(FrameType.PING.wireCode());
        buf.putShort((short) 0);
        buf.putInt(0);
        RingProtocolException ex =
                assertThrows(RingProtocolException.class, () -> feed(buf.array()));
        assertTrue(ex.getMessage().contains("unsupported wire version"), ex.getMessage());
    }

    @Test
    void unknownFrameType_throwsRingProtocolException() {
        ByteBuffer buf = ByteBuffer.allocate(RingProtocol.HEADER_BYTES);
        buf.put(RingProtocol.MAGIC);
        buf.put(RingProtocol.VERSION);
        buf.put((byte) 0xFE); // unknown type code
        buf.putShort((short) 0);
        buf.putInt(0);
        RingProtocolException ex =
                assertThrows(RingProtocolException.class, () -> feed(buf.array()));
        assertTrue(ex.getMessage().contains("unknown frame type"), ex.getMessage());
    }

    @Test
    void negativeBodyLength_throwsRingProtocolException() {
        ByteBuffer buf = ByteBuffer.allocate(RingProtocol.HEADER_BYTES);
        buf.put(RingProtocol.MAGIC);
        buf.put(RingProtocol.VERSION);
        buf.put(FrameType.PING.wireCode());
        buf.putShort((short) 0);
        buf.putInt(-1); // negative length
        RingProtocolException ex =
                assertThrows(RingProtocolException.class, () -> feed(buf.array()));
        assertTrue(ex.getMessage().contains("negative body length"), ex.getMessage());
    }

    @Test
    void oversizedBodyLength_throwsFrameTooLargeException() {
        FrameDecoder small = new FrameDecoder(1024);
        ByteBuffer   buf   = ByteBuffer.allocate(RingProtocol.HEADER_BYTES);
        buf.put(RingProtocol.MAGIC);
        buf.put(RingProtocol.VERSION);
        buf.put(FrameType.EVENT.wireCode());
        buf.putShort((short) 0);
        buf.putInt(2048); // 2 KiB > 1 KiB cap
        FrameTooLargeException ex =
                assertThrows(
                             FrameTooLargeException.class,
                             () -> small.tryDecode(ByteBuffer.wrap(buf.array()), emitted::add));
        assertEquals(2048, ex.announcedBytes());
        assertEquals(1024, ex.maxBytes());
    }

    // ---- Reset / re-use --------------------------------------------------------

    @Test
    void decoderResumesAfterCompletedFrame_canHandleNextFrame() {
        byte[] f1 = FrameEncoder.encode(new RingFrame(FrameType.PING, new byte[]{1}));
        byte[] f2 = FrameEncoder.encode(new RingFrame(FrameType.PONG, new byte[]{2}));
        feed(f1);
        assertEquals(1, emitted.size());
        feed(f2);
        assertEquals(2, emitted.size());
        assertEquals(FrameType.PONG, emitted.get(1).type());
    }

    // ---- Constructor validation ------------------------------------------------

    @Test
    void constructor_rejectsZeroOrNegativeMaxBodyBytes() {
        assertThrows(IllegalArgumentException.class, () -> new FrameDecoder(0));
        assertThrows(IllegalArgumentException.class, () -> new FrameDecoder(-1));
    }

    @Test
    void tryDecode_rejectsNullArgs() {
        FrameDecoder d = new FrameDecoder();
        assertThrows(NullPointerException.class, () -> d.tryDecode(null, emitted::add));
        assertThrows(NullPointerException.class, () -> d.tryDecode(ByteBuffer.allocate(0), null));
    }

    // ---- Round-trip equivalence ------------------------------------------------

    @Test
    void encodeDecodeRoundTrip_preservesEveryFrameType() {
        for (FrameType type : FrameType.values()) {
            byte[]          body      = ("body-of-" + type.name()).getBytes(StandardCharsets.UTF_8);
            byte[]          encoded   = FrameEncoder.encode(new RingFrame(type, body));
            List<RingFrame> roundTrip = new ArrayList<>();
            new FrameDecoder().tryDecode(ByteBuffer.wrap(encoded), roundTrip::add);
            assertEquals(1, roundTrip.size(), "round-trip for " + type);
            assertEquals(type, roundTrip.getFirst().type());
            assertTrue(Arrays.equals(body, roundTrip.getFirst().bodyBytes()), "body mismatch for " + type);
        }
    }
}
