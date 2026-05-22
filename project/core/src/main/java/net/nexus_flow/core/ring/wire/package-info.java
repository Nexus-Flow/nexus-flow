/**
 * Ring wire protocol — pure binary framing and codecs. No I/O, no threading, no sockets.
 *
 * <h2>Frame layout</h2>
 *
 * <pre>
 * 0 1 2 3 4 5 6 7 8 9 10 11
 * +-------+-------+-------+-------+-------+-------+-------+-------+-------+-------+-------+-------+
 * | magic[4] = "NXFR" | ver | type | flags[2] | body length |
 * +-------+-------+-------+-------+-------+-------+-------+-------+-------+-------+-------+-------+
 * | body bytes ...
 * +-------+-------+-------+-------+-------+-------+-------+-------+-------+-------+-------+-------+
 * </pre>
 *
 * <ul>
 * <li>{@code magic} — 4 ASCII bytes {@code 'N','X','F','R'} (0x4E 0x58 0x46 0x52). Identifies
 * Nexus Flow ring frames; lets a TCP stream reject a connection whose first bytes are
 * garbage / a different protocol immediately.
 * <li>{@code ver} — unsigned byte. {@link net.nexus_flow.core.ring.wire.RingProtocol#VERSION
 * Current version} is 1. Receivers reject frames whose version they cannot decode.
 * <li>{@code type} — unsigned byte mapped to {@link net.nexus_flow.core.ring.wire.FrameType}
 * via explicit wire codes (NOT enum ordinal — wire codes are stable across enum
 * reordering, ordinals are not).
 * <li>{@code flags} — 2 reserved bytes for future per-frame flags (compression, signing, etc.).
 * Must be sent as zero in {@code VERSION = 1}.
 * <li>{@code body length} — unsigned 32-bit big-endian. Maximum
 * {@link net.nexus_flow.core.ring.wire.RingProtocol#DEFAULT_MAX_BODY_BYTES} bytes; receivers
 * enforce their own cap and close the connection on excess.
 * <li>{@code body} — opaque to the framing layer; per-frame-type encoders/decoders in this
 * package (e.g. {@link net.nexus_flow.core.ring.wire.HelloPayload}) own the body format.
 * </ul>
 *
 * <h2>Byte order</h2>
 *
 * Network byte order (big-endian) for every multi-byte field, including those inside the body.
 * Operators reading a hex dump can compare bytes to the {@code RingProtocol} constants without
 * doing endianness arithmetic.
 *
 * <h2>Why explicit wire codes for FrameType</h2>
 *
 * {@link Enum#ordinal()} changes when an enum constant is added or reordered, which silently
 * breaks the wire protocol across versions. Every {@link net.nexus_flow.core.ring.wire.FrameType}
 * constant carries an explicit {@link net.nexus_flow.core.ring.wire.FrameType#wireCode()} that is
 * frozen for the life of the protocol; new types get new codes, never reuse retired ones.
 *
 * <h2>Security boundary</h2>
 *
 * The wire layer trusts the bytes it parses to come from a TLS-terminated socket — the I/O layer
 * (R2/R3) enforces mTLS before any frame reaches this code. The wire layer ALSO enforces
 * defensive caps (frame length, type-name length, fingerprint count) so a misbehaving peer
 * (compromised cert, buggy peer) cannot DoS via crafted frames.
 *
 * <h2>What is NOT in this package</h2>
 *
 * No sockets, no threads, no selectors, no TLS — those live in the transport package (R2/R3).
 * No business meaning of frame types — that lives in the corresponding feature package
 * (event/registry/dispatch/saga). This is the smallest reusable unit.
 */
package net.nexus_flow.core.ring.wire;
