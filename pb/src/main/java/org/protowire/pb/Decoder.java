// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pb;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads the messages an {@link Encoder} writes: varint-delimited frames, as
 * protowire-go's {@code pb.Decoder} (#207).
 *
 * <p>A frame larger than {@link #setMaxFrameSize} (16 MiB by default), or a
 * length varint that overflows, throws a {@link FramingCorruptException}; a
 * stream that ends inside a frame throws {@link EOFException}
 * {@code unexpected EOF}. Either leaves the stream out of step, so every later
 * call throws the same exception. A frame whose message does not decode
 * throws that error once, and the next call reads the next frame.
 */
public final class Decoder {
    /** The default cap on one frame's length, 16 MiB, as protowire-go's {@code DefaultMaxFrameSize}. */
    public static final int DEFAULT_MAX_FRAME_SIZE = 16 << 20;

    private final InputStream in;
    private final UnmarshalOptions opts;
    private int max = DEFAULT_MAX_FRAME_SIZE;
    private IOException err;
    private boolean ended; // set by readUvarint at the end of the stream between frames

    public Decoder(InputStream in) { this(in, UnmarshalOptions.defaults()); }

    /** A decoder whose frames decode under {@code opts}: the per-call limits, which still bound a frame's message. */
    public Decoder(InputStream in, UnmarshalOptions opts) {
        this.in = in;
        this.opts = opts;
    }

    public void setMaxFrameSize(int n) { this.max = n; }

    /**
     * Reads the next frame into {@code dest}, a struct with {@link ProtoField}
     * fields, as {@link Pb#unmarshal(byte[], Object, UnmarshalOptions)} does.
     *
     * @return false at the end of the stream, between frames
     */
    public boolean decode(Object dest) throws IOException {
        byte[] frame = nextFrame();
        if (frame == null) return false;
        Pb.unmarshal(frame, dest, opts);
        return true;
    }

    /**
     * The next frame's bytes, undecoded, or null at the end of the stream
     * between frames. For a codec whose messages are not struct-bound, such
     * as the envelope's, so the framing has one implementation.
     */
    public byte[] nextFrame() throws IOException {
        if (err != null) throw err;
        long n;
        try {
            n = readUvarint();
        } catch (IOException e) {
            err = e;
            throw e;
        }
        if (ended) return null;
        if (Long.compareUnsigned(n, max) > 0) {
            throw err = new FramingCorruptException("frame size " + Long.toUnsignedString(n) + " exceeds max " + max);
        }
        byte[] buf = new byte[(int) n];
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) throw err = new EOFException("unexpected EOF");
            off += r;
        }
        return buf;
    }

    /**
     * The frame length, as Go's {@code binary.ReadUvarint} reads it: at the end
     * of the stream before its first byte, {@link #ended} is set; inside it,
     * {@code unexpected EOF}; past ten bytes or 64 bits, Go's overflow error.
     * The value is unsigned.
     */
    private long readUvarint() throws IOException {
        long x = 0;
        int s = 0;
        for (int i = 0; i < 10; i++) {
            int b = in.read();
            if (b < 0) {
                if (i == 0) {
                    ended = true;
                    return 0;
                }
                throw new EOFException("unexpected EOF");
            }
            if (b < 0x80) {
                if (i == 9 && b > 1) break;
                return x | ((long) b << s);
            }
            x |= (long) (b & 0x7f) << s;
            s += 7;
        }
        throw new FramingCorruptException("binary: varint overflows a 64-bit integer");
    }
}
