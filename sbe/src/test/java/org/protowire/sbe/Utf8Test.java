// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.protowire.sbe.testproto.Text;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The UTF-8 rules for an {@code (sbe.length)} field, which follow the field's
 * type. On decode a string is validated (HARDENING.md § UTF-8) and bytes are
 * not. {@link Text} lays out {@code text} at 0 and {@code raw} at 8, each 8
 * bytes, after the 8-byte header.
 */
class Utf8Test {
    private static final Codec CODEC = Codec.of(Text.getDescriptor().getFile());

    /** A Text message on the wire: header, then the two 8-byte fields. */
    private static byte[] wire(int[] text, int[] raw) {
        byte[] b = new byte[8 + 16];
        b[0] = 16;              // blockLength
        b[2] = 2;               // templateId
        b[4] = 1;               // schemaId
        for (int i = 0; i < text.length; i++) b[8 + i] = (byte) text[i];
        for (int i = 0; i < raw.length; i++) b[16 + i] = (byte) raw[i];
        return b;
    }

    private static Text decode(byte[] data) {
        Text.Builder b = Text.newBuilder();
        CODEC.unmarshal(data, b);
        return b.build();
    }

    @Test
    void invalidStringIsRejected() {
        byte[] data = wire(new int[]{0x41, 0x42, 0xFF, 0xFE}, new int[0]);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> decode(data));
        assertEquals("sbe: invalid UTF-8 in string field text", e.getMessage());
    }

    @Test
    void splitCharacterIsRejected() {
        // What a byte-exact cut of ABCDEFGé at 8 writes: the lead byte of é
        // with its continuation byte cut off.
        byte[] data = wire(new int[]{0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0xC3}, new int[0]);
        assertThrows(IllegalArgumentException.class, () -> decode(data));
    }

    @Test
    void encodedSurrogateIsRejected() {
        // ED A0 80 is U+D800 written as UTF-8, which UTF-8 forbids.
        byte[] data = wire(new int[]{0xED, 0xA0, 0x80}, new int[0]);
        assertThrows(IllegalArgumentException.class, () -> decode(data));
    }

    @Test
    void validStringFillingTheFieldIsAccepted() {
        byte[] data = wire(new int[]{0x63, 0x61, 0x66, 0xC3, 0xA9, 0xE2, 0x82, 0xAC}, new int[0]);
        assertEquals("café€", decode(data).getText());
    }

    @Test
    void invalidBytesFieldIsAccepted() {
        byte[] data = wire(new int[]{0x41, 0x42}, new int[]{0x41, 0x42, 0xFF, 0xFE});
        Text t = decode(data);
        assertEquals("AB", t.getText());
        assertEquals(ByteString.copyFrom(new byte[]{0x41, 0x42, (byte) 0xFF, (byte) 0xFE, 0, 0, 0, 0}), t.getRaw());
    }

    @Test
    void viewRejectsInvalidString() {
        View v = CODEC.view(wire(new int[]{0x41, 0x42, 0xFF, 0xFE}, new int[]{0xFF}));
        assertThrows(IllegalArgumentException.class, () -> v.stringValue("text"));
        assertArrayEquals(new byte[]{(byte) 0xFF, 0, 0, 0, 0, 0, 0, 0}, v.bytesValue("raw"));
    }
}
