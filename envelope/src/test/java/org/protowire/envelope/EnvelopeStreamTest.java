// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.envelope;

import org.junit.jupiter.api.Test;
import org.protowire.envelope.v1.Envelope;
import org.protowire.pb.FramingCorruptException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link Encoder} and {@link Decoder} frame envelopes as protowire-go's
 * envelope stream does (#207). {@link #GO} is the stream protowire-go
 * v1.17.0's {@code scripts/dump_stream_envelope} writes: an OK, a structured
 * error with a field detail and a meta entry, and a transport error.
 */
class EnvelopeStreamTest {

    static final byte[] GO = HexFormat.of().parseHex("0908c8011a04deadbeef7b08920322760a12494e53554646494349454e545f46554e4453"
        + "120f62616c616e636520746f6f206c6f771a0524332e35301a062431302e303022290a06616d6f756e7412094d494e5f56414c55451a0d"
        + "62656c6f77206d696e696d756d220531302e30302a150a0a726571756573745f696412077265712d313233141212636f6e6e656374696f"
        + "6e2072656675736564");

    /** The three envelopes Go's dumper writes. */
    static List<Envelope> envelopes() {
        Envelope ok = Envelopes.ok(200, new byte[] {(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef});
        Envelope bad = Envelopes.err(402, "INSUFFICIENT_FUNDS", "balance too low", "$3.50", "$10.00");
        bad = bad.toBuilder().setError(Envelopes.withMeta(
            Envelopes.withField(bad.getError(), "amount", "MIN_VALUE", "below minimum", "10.00"),
            "request_id", "req-123")).build();
        return List.of(ok, bad, Envelopes.transportErr("connection refused"));
    }

    @Test
    void theEncoderWritesGosStream() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Encoder enc = new Encoder(out);
        for (Envelope e : envelopes()) enc.encode(e);
        assertEquals(HexFormat.of().formatHex(GO), HexFormat.of().formatHex(out.toByteArray()));
    }

    @Test
    void theDecoderReadsGosStream() throws IOException {
        Decoder d = new Decoder(new ByteArrayInputStream(GO));
        for (Envelope want : envelopes()) assertEquals(want, d.decode());
        assertNull(d.decode(), "the end of the stream");
    }

    /** The framing is pb's, and so are its errors. */
    @Test
    void aFramePastTheMaximumIsPbsCorruptFraming() {
        Decoder d = new Decoder(new ByteArrayInputStream(GO));
        d.setMaxFrameSize(8);
        assertEquals("pb: stream framing corrupt: frame size 9 exceeds max 8",
            assertThrows(FramingCorruptException.class, d::decode).getMessage());
    }
}
