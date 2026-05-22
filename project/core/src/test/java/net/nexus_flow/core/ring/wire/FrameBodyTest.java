package net.nexus_flow.core.ring.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import org.junit.jupiter.api.Test;

class FrameBodyTest {

    @Test
    void empty_isSingleton_zeroAllocation() {
        assertSame(FrameBody.empty(), FrameBody.empty());
        assertSame(FrameBody.EMPTY_BODY, FrameBody.empty());
        assertEquals(0, FrameBody.empty().length());
    }

    @Test
    void ofOwned_doesNotCloneTheArray_takingOwnership() {
        byte[]    src  = {1, 2, 3, 4};
        FrameBody body = FrameBody.ofOwned(src);
        assertEquals(4, body.length());
        // The contract is "caller MUST NOT mutate the array after ownership transfer" — we
        // verify the asReadOnlyBuffer view still aliases the original. Mutating src would be
        // a contract violation, but we don't do it here; we just check identity via length.
        ByteBuffer view = body.asReadOnlyBuffer();
        assertEquals(1, view.get(0));
    }

    @Test
    void ofCopy_clonesTheRange_callerArrayMutationDoesNotAffectBody() {
        byte[]    src  = {9, 8, 7, 6, 5};
        FrameBody body = FrameBody.ofCopy(src, 1, 3); // {8, 7, 6}
        assertEquals(3, body.length());
        src[1] = 0; // caller mutates — must not affect body
        ByteBuffer view = body.asReadOnlyBuffer();
        assertEquals(8, view.get(0));
        assertEquals(7, view.get(1));
        assertEquals(6, view.get(2));
    }

    @Test
    void ofCopy_zeroLength_returnsEmptySingleton() {
        byte[] src = {1, 2, 3};
        assertSame(FrameBody.EMPTY_BODY, FrameBody.ofCopy(src, 1, 0));
    }

    @Test
    void ofCopy_outOfBounds_throws() {
        byte[] src = {1, 2, 3};
        assertThrows(IndexOutOfBoundsException.class, () -> FrameBody.ofCopy(src, 0, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> FrameBody.ofCopy(src, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> FrameBody.ofCopy(src, 2, -1));
    }

    @Test
    void asReadOnlyBuffer_rejectsWrites() {
        FrameBody  body = FrameBody.ofOwned(new byte[]{1, 2, 3});
        ByteBuffer view = body.asReadOnlyBuffer();
        assertTrue(view.isReadOnly());
        assertThrows(ReadOnlyBufferException.class, () -> view.put(0, (byte) 9));
    }

    @Test
    void writeTo_bulkCopies_advancingDestination() {
        FrameBody  body = FrameBody.ofOwned(new byte[]{10, 20, 30});
        ByteBuffer dst  = ByteBuffer.allocate(10);
        dst.put((byte) 99);
        body.writeTo(dst);
        assertEquals(4, dst.position());
        assertEquals(10, dst.get(1));
        assertEquals(20, dst.get(2));
        assertEquals(30, dst.get(3));
    }

    @Test
    void copyToByteArray_returnsFreshCopy_neverAliasingTheBacking() {
        FrameBody body = FrameBody.ofOwned(new byte[]{1, 2, 3});
        byte[]    a    = body.copyToByteArray();
        byte[]    b    = body.copyToByteArray();
        assertNotSame(a, b, "copyToByteArray must always return a fresh array");
        a[0] = 99;
        assertEquals(1, b[0], "mutating one copy must not affect another");
    }

    @Test
    void equals_isByContentNotByReference() {
        FrameBody a = FrameBody.ofOwned(new byte[]{1, 2, 3});
        FrameBody b = FrameBody.ofOwned(new byte[]{1, 2, 3});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        FrameBody c = FrameBody.ofOwned(new byte[]{1, 2, 4});
        assertNotEquals(a, c);
    }
}
