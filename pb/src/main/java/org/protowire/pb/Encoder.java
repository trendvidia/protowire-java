// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pb;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes pb-encoded messages to a stream, each framed by the protobuf
 * delimited convention: its length as a varint, then its bytes. As
 * protowire-go's {@code pb.Encoder} (#207), so a {@link Decoder} in either
 * port reads what the other writes.
 */
public final class Encoder {
    private final OutputStream out;
    private final MarshalOptions opts;

    public Encoder(OutputStream out) { this(out, MarshalOptions.defaults()); }

    public Encoder(OutputStream out, MarshalOptions opts) {
        this.out = out;
        this.opts = opts;
    }

    /** Writes {@code v}, a struct with {@link ProtoField} fields, as {@link Pb#marshal} writes it, in one frame. */
    public void encode(Object v) throws IOException {
        encodeFrame(Pb.marshal(v, opts));
    }

    /**
     * Writes {@code body}, already encoded, as one frame. For a codec whose
     * messages are not struct-bound, such as the envelope's, so the framing
     * has one implementation.
     */
    public void encodeFrame(byte[] body) throws IOException {
        long n = body.length;
        byte[] hdr = new byte[10];
        int i = 0;
        while (n >= 0x80) {
            hdr[i++] = (byte) (n | 0x80);
            n >>>= 7;
        }
        hdr[i++] = (byte) n;
        out.write(hdr, 0, i);
        out.write(body);
    }
}
