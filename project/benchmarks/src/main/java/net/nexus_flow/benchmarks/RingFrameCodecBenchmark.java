package net.nexus_flow.benchmarks;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import net.nexus_flow.core.ring.wire.FrameBody;
import net.nexus_flow.core.ring.wire.FrameEncoder;
import net.nexus_flow.core.ring.wire.FrameType;
import net.nexus_flow.core.ring.wire.RingFrame;
import net.nexus_flow.core.ring.wire.RingFrameCodec;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Compares the three encode paths exposed by the ring wire layer:
 *
 * <ul>
 * <li>{@code staticEncode} — direct call to the {@link FrameEncoder} static helper. No
 * virtual dispatch.
 * <li>{@code byteBufferStrategy} — {@link RingFrameCodec#BYTE_BUFFER} interface dispatch
 * over the same {@link FrameEncoder} implementation. Measures the cost of the strategy
 * indirection.
 * <li>{@code memorySegmentStrategy} — {@link RingFrameCodec#MEMORY_SEGMENT} interface
 * dispatch over the {@link java.lang.foreign.MemorySegment#copy} body path. Measures the
 * crossover point where the intrinsic copy amortises its setup cost.
 * </ul>
 *
 * <p>Bodies are exercised at 16 / 64 / 256 / 1024 / 4096 bytes so the crossover between the
 * default codec (cheaper for tiny frames) and the MemorySegment codec (cheaper for larger
 * frames via the {@code memmove} intrinsic) shows up directly in the table.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class RingFrameCodecBenchmark {

    @Param({"16", "64", "256", "1024", "4096"})
    public int bodySize;

    private RingFrame      frame;
    private ByteBuffer     dst;
    private RingFrameCodec byteBufferCodec;
    private RingFrameCodec memorySegmentCodec;

    @Setup
    public void setup() {
        byteBufferCodec    = RingFrameCodec.BYTE_BUFFER;
        memorySegmentCodec = RingFrameCodec.MEMORY_SEGMENT;
        byte[] body = new byte[bodySize];
        for (int i = 0; i < bodySize; i++) {
            body[i] = (byte) (i & 0xFF);
        }
        frame = new RingFrame(FrameType.EVENT, FrameBody.ofOwned(body));
        dst   = ByteBuffer.allocate(bodySize + 64);
    }

    @Benchmark
    public ByteBuffer staticEncode() {
        dst.clear();
        FrameEncoder.encodeInto(frame, dst, 1 << 20);
        return dst;
    }

    @Benchmark
    public ByteBuffer byteBufferStrategy() {
        dst.clear();
        byteBufferCodec.encodeInto(frame, dst, 1 << 20);
        return dst;
    }

    @Benchmark
    public ByteBuffer memorySegmentStrategy() {
        dst.clear();
        memorySegmentCodec.encodeInto(frame, dst, 1 << 20);
        return dst;
    }
}
