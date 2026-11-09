// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pb;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Encoder} and {@link Decoder} frame pb messages as protowire-go's
 * pb stream does (#207). {@link #GO} is the stream protowire-go v1.17.0's
 * {@code scripts/dump_stream_pb} writes; each error text and the decoder's
 * behaviour after it are Go's, from probes of {@code pb.Decoder}.
 */
class PbStreamTest {

    public static class StreamMsg {
        @ProtoField(1) long n;
        @ProtoField(2) String text;
        @ProtoField(3) byte[] data;
        public StreamMsg() {}
        StreamMsg(long n, String text, byte[] data) { this.n = n; this.text = text; this.data = data; }
    }

    /** {@code {N: 1, Text: "hello"}}, {@code {N: 42, Data: DEADBEEF}}, {@code {N: 1<<20, Text: "third"}}. */
    static final byte[] GO = HexFormat.of().parseHex("090801120568656c6c6f08082a1a04deadbeef0b0880804012057468697264");

    private static Decoder decoder(byte[] in) { return new Decoder(new ByteArrayInputStream(in)); }

    @Test
    void theEncoderWritesGosStream() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Encoder enc = new Encoder(out);
        enc.encode(new StreamMsg(1, "hello", null));
        enc.encode(new StreamMsg(42, null, new byte[] {(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef}));
        enc.encode(new StreamMsg(1 << 20, "third", null));
        assertEquals(HexFormat.of().formatHex(GO), HexFormat.of().formatHex(out.toByteArray()));
    }

    @Test
    void theDecoderReadsGosStream() throws IOException {
        Decoder d = decoder(GO);
        StreamMsg m = new StreamMsg();
        assertTrue(d.decode(m));
        assertEquals(1, m.n);
        assertEquals("hello", m.text);
        m = new StreamMsg();
        assertTrue(d.decode(m));
        assertEquals(42, m.n);
        assertArrayEquals(new byte[] {(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef}, m.data);
        m = new StreamMsg();
        assertTrue(d.decode(m));
        assertEquals(1 << 20, m.n);
        assertEquals("third", m.text);
        assertFalse(d.decode(new StreamMsg()), "the end of the stream");
        assertFalse(d.decode(new StreamMsg()), "and again");
    }

    /** A framing error leaves the stream out of step, so it is thrown again on every later call, as Go's sticky error. */
    private static void sticky(Decoder d, Class<? extends IOException> type, String message) {
        IOException first = assertThrows(type, () -> d.decode(new StreamMsg()));
        assertEquals(message, first.getMessage());
        assertSame(first, assertThrows(IOException.class, () -> d.decode(new StreamMsg())));
    }

    @Test
    void aFramePastTheMaximumIsCorruptFraming() {
        Decoder d = decoder(GO);
        d.setMaxFrameSize(8);
        sticky(d, FramingCorruptException.class, "pb: stream framing corrupt: frame size 9 exceeds max 8");
    }

    @Test
    void aVarintPastSixtyFourBitsIsCorruptFraming() {
        byte[] over = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x01};
        sticky(decoder(over), FramingCorruptException.class, "pb: stream framing corrupt: binary: varint overflows a 64-bit integer");
    }

    @Test
    void aStreamEndingInsideAFrameIsAnUnexpectedEof() {
        sticky(decoder(Arrays.copyOf(GO, 5)), EOFException.class, "unexpected EOF");
        sticky(decoder(new byte[] {(byte) 0x80}), EOFException.class, "unexpected EOF");
    }

    /** A frame whose message does not decode fails alone: the next call reads the next frame. */
    @Test
    void aBadMessageDoesNotStopTheStream() throws IOException {
        byte[] in = new byte[GO.length + 2];
        in[0] = 0x01;
        in[1] = 0x0f; // field 1, wire type 7: no message
        System.arraycopy(GO, 0, in, 2, GO.length);
        Decoder d = decoder(in);
        assertThrows(IOException.class, () -> d.decode(new StreamMsg()));
        StreamMsg m = new StreamMsg();
        assertTrue(d.decode(m));
        assertEquals("hello", m.text);
    }
}
