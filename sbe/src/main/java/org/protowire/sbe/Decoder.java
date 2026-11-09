// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import com.google.protobuf.Message;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Reads the messages an {@link Encoder} writes: SOFH-framed SBE, as
 * protowire-go's {@code sbe.Decoder} (#207). Made by {@link Codec#newDecoder}.
 *
 * <p>An encoding type other than 0xEB50, a total length below the header's,
 * or a frame larger than {@link #setMaxFrameSize} (16 MiB by default) throws
 * a {@link FramingCorruptException}; a stream that ends inside a frame throws
 * {@link EOFException} {@code unexpected EOF}. Either leaves the stream out of
 * step, so every later call throws the same exception. A frame whose message
 * does not decode throws that error once, and the next call reads the next
 * frame.
 */
public final class Decoder {
    /** The default cap on one frame's body, 16 MiB, as protowire-go's {@code DefaultMaxFrameSize}. */
    public static final int DEFAULT_MAX_FRAME_SIZE = 16 << 20;

    private final Codec codec;
    private final InputStream in;
    private int max = DEFAULT_MAX_FRAME_SIZE;
    private IOException err;

    Decoder(Codec codec, InputStream in) {
        this.codec = codec;
        this.in = in;
    }

    public void setMaxFrameSize(int n) { this.max = n; }

    /**
     * Reads the next frame into {@code b}, as {@link Codec#unmarshal} does.
     *
     * @return false at the end of the stream, between frames
     */
    public boolean decode(Message.Builder b) throws IOException {
        if (err != null) throw err;
        byte[] hdr = new byte[Encoder.SOFH_HEADER_LEN];
        int got = readFully(hdr);
        if (got == 0) return false;
        if (got < hdr.length) throw err = new EOFException("unexpected EOF");
        ByteBuffer h = ByteBuffer.wrap(hdr); // big-endian
        long total = Integer.toUnsignedLong(h.getInt(0));
        int enc = Short.toUnsignedInt(h.getShort(4));
        if (enc != Encoder.SOFH_SBE_LE) {
            throw err = new FramingCorruptException(String.format("SOFH encoding type 0x%04x not supported", enc));
        }
        if (total < Encoder.SOFH_HEADER_LEN) {
            throw err = new FramingCorruptException("SOFH total length " + total + " < header size");
        }
        long bodyLen = total - Encoder.SOFH_HEADER_LEN;
        if (bodyLen > max) {
            throw err = new FramingCorruptException("frame size " + bodyLen + " exceeds max " + max);
        }
        byte[] body = new byte[(int) bodyLen];
        if (readFully(body) < body.length) throw err = new EOFException("unexpected EOF");
        codec.unmarshal(body, b);
        return true;
    }

    /** Reads until {@code buf} is full or the stream ends; the count read. */
    private int readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }
}
