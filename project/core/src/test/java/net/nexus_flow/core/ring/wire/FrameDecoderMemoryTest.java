package net.nexus_flow.core.ring.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrameDecoderMemoryTest {

    @Test
    void pendingBytes_zero_beforeAnyInput() {
        FrameDecoder d = new FrameDecoder();
        assertEquals(0, d.pendingBytes());
    }

    @Test
    void pendingBytes_growsIncrementally_notToAnnouncedLength() {
        // Build a header announcing a 1 MiB body but feed only a few body bytes.
        ByteBuffer header = ByteBuffer.allocate(RingProtocol.HEADER_BYTES)
                .order(java.nio.ByteOrder.BIG_ENDIAN);
        header.put(RingProtocol.MAGIC);
        header.put(RingProtocol.VERSION);
        header.put(FrameType.EVENT.wireCode());
        header.putShort((short) 0);
        header.putInt(1024 * 1024); // 1 MiB body announced

        FrameDecoder    d   = new FrameDecoder(8 * 1024 * 1024);
        List<RingFrame> out = new ArrayList<>();
        d.tryDecode(ByteBuffer.wrap(header.array()), out::add);
        // Header consumed; no body bytes yet — pendingBytes MUST NOT include the announced
        // 1 MiB just because the header said so.
        assertEquals(0, out.size(), "frame is not yet emitted");
        assertTrue(d.pendingBytes() < 64 * 1024,
                   "decoder must not pre-allocate the full announced body; pendingBytes="
                           + d.pendingBytes());

        // Feed 10 body bytes — buffer grows just enough.
        d.tryDecode(ByteBuffer.wrap(new byte[10]), out::add);
        assertTrue(d.pendingBytes() < 64 * 1024 + 10,
                   "after 10 body bytes, pendingBytes must still be far below 1 MiB; was "
                           + d.pendingBytes());
    }

    @Test
    void announcedLengthExceedingCap_throwsFrameTooLarge_notMemoryAllocation() {
        FrameDecoder d      = new FrameDecoder(1024);
        ByteBuffer   header = ByteBuffer.allocate(RingProtocol.HEADER_BYTES)
                .order(java.nio.ByteOrder.BIG_ENDIAN);
        header.put(RingProtocol.MAGIC);
        header.put(RingProtocol.VERSION);
        header.put(FrameType.EVENT.wireCode());
        header.putShort((short) 0);
        header.putInt(2048); // exceeds 1 KiB cap
        assertThrows(FrameTooLargeException.class,
                     () -> d.tryDecode(ByteBuffer.wrap(header.array()), _f -> {
                     }));
    }

    @Test
    void mediumFrame_doublingGrowth_doesNotOverAllocate() {
        // Encode a 32 KiB frame, feed it in 1 KiB chunks. Buffer should double from 4 KiB to
        // 8 KiB to 16 KiB to 32 KiB — never larger than the announced 32 KiB.
        byte[] body = new byte[32 * 1024];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i & 0xFF);
        }
        byte[] encoded = FrameEncoder.encode(new RingFrame(FrameType.EVENT, body));

        FrameDecoder    d      = new FrameDecoder();
        List<RingFrame> out    = new ArrayList<>();
        int             offset = 0;
        int             chunk  = 1024;
        while (offset < encoded.length) {
            int take = Math.min(chunk, encoded.length - offset);
            d.tryDecode(ByteBuffer.wrap(encoded, offset, take), out::add);
            offset += take;
            // pendingBytes is always bounded above by the body length (the buffer never
            // grows past the announced size).
            assertTrue(d.pendingBytes() <= 32 * 1024 + RingProtocol.HEADER_BYTES,
                       "pendingBytes must not exceed announced + header: " + d.pendingBytes());
        }
        assertEquals(1, out.size());
        assertEquals(32 * 1024, out.getFirst().body().length());
    }
}
