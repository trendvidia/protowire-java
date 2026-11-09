// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import org.junit.jupiter.api.Test;
import stream.v1.Stream.Simple;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Codec#newEncoder} and {@link Codec#newDecoder} frame SBE messages
 * with a Simple Open Framing Header as protowire-go's SBE stream does (#207).
 * {@link #GO} is the stream protowire-go v1.17.0's
 * {@code scripts/dump_stream_sbe} writes, on the same schema
 * ({@code stream/stream.proto}); each error text and the decoder's behaviour
 * after it are Go's, from probes of {@code sbe.Decoder}.
 */
final class SbeStreamTest {

    private static final Codec CODEC = Codec.of(Simple.getDescriptor().getFile());

    /** {@code Simple{1, -100}}, {@code Simple{2, 0}}, {@code Simple{1<<20, MaxInt32}}. */
    static final byte[] GO = HexFormat.of().parseHex("00000016eb500800020001000000010000009cffffff00000016eb5008000200010000000200000000"
        + "00000000000016eb50080002000100000000001000ffffff7f");

    private static Decoder decoder(byte[] in) { return CODEC.newDecoder(new ByteArrayInputStream(in)); }

    @Test
    void theEncoderWritesGosStream() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Encoder enc = CODEC.newEncoder(out);
        enc.encode(Simple.newBuilder().setId(1).setValue(-100).build());
        enc.encode(Simple.newBuilder().setId(2).setValue(0).build());
        enc.encode(Simple.newBuilder().setId(1 << 20).setValue(Integer.MAX_VALUE).build());
        assertEquals(HexFormat.of().formatHex(GO), HexFormat.of().formatHex(out.toByteArray()));
    }

    @Test
    void theDecoderReadsGosStream() throws IOException {
        Decoder d = decoder(GO);
        Simple.Builder b = Simple.newBuilder();
        assertTrue(d.decode(b));
        assertEquals(Simple.newBuilder().setId(1).setValue(-100).build(), b.build());
        b = Simple.newBuilder();
        assertTrue(d.decode(b));
        assertEquals(2, b.getId());
        b = Simple.newBuilder();
        assertTrue(d.decode(b));
        assertEquals(Simple.newBuilder().setId(1 << 20).setValue(Integer.MAX_VALUE).build(), b.build());
        assertFalse(d.decode(Simple.newBuilder()), "the end of the stream");
        assertFalse(d.decode(Simple.newBuilder()), "and again");
    }

    private static void sticky(Decoder d, Class<? extends IOException> type, String message) {
        IOException first = assertThrows(type, () -> d.decode(Simple.newBuilder()));
        assertEquals(message, first.getMessage());
        assertSame(first, assertThrows(IOException.class, () -> d.decode(Simple.newBuilder())));
    }

    @Test
    void corruptFramingIsGosError() {
        Decoder max8 = decoder(GO);
        max8.setMaxFrameSize(8);
        sticky(max8, FramingCorruptException.class, "sbe: stream framing corrupt: frame size 16 exceeds max 8");
        sticky(decoder(new byte[] {0, 0, 0, 0x16, (byte) 0xeb, 0x51}), FramingCorruptException.class,
            "sbe: stream framing corrupt: SOFH encoding type 0xeb51 not supported");
        sticky(decoder(new byte[] {0, 0, 0, 5, (byte) 0xeb, 0x50}), FramingCorruptException.class,
            "sbe: stream framing corrupt: SOFH total length 5 < header size");
    }

    @Test
    void aStreamEndingInsideAFrameIsAnUnexpectedEof() {
        sticky(decoder(new byte[] {0, 0, 0}), EOFException.class, "unexpected EOF");
        sticky(decoder(Arrays.copyOf(GO, 10)), EOFException.class, "unexpected EOF");
    }
}
