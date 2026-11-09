// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import com.google.protobuf.Message;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Writes SBE messages to a stream, each framed by a Simple Open Framing
 * Header: the frame's total length, header included, as a big-endian
 * uint32, then the encoding type 0xEB50 (SBE 1.0, little-endian) as a
 * big-endian uint16. As protowire-go's {@code sbe.Encoder} (#207), so a
 * {@link Decoder} in either port reads what the other writes. Made by
 * {@link Codec#newEncoder}.
 */
public final class Encoder {
    /** The FIX SOFH encoding type of SBE 1.0 little-endian, the byte order this codec writes. */
    static final int SOFH_SBE_LE = 0xEB50;
    static final int SOFH_HEADER_LEN = 6;

    private final Codec codec;
    private final OutputStream out;

    Encoder(Codec codec, OutputStream out) {
        this.codec = codec;
        this.out = out;
    }

    /** Writes {@code msg}, as {@link Codec#marshal} writes it, in one frame. */
    public void encode(Message msg) throws IOException {
        byte[] body = codec.marshal(msg);
        ByteBuffer hdr = ByteBuffer.allocate(SOFH_HEADER_LEN); // big-endian
        hdr.putInt(SOFH_HEADER_LEN + body.length);
        hdr.putShort((short) SOFH_SBE_LE);
        out.write(hdr.array());
        out.write(body);
    }
}
