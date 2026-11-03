// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe.it;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.protowire.sbe.testproto.Text;
import org.protowire.sbe.testproto.TextSbeCodec;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The lite tier's UTF-8 rules for an {@code (sbe.length)} field, through the
 * codegen-emitted {@link TextSbeCodec}. The same cases as {@code :sbe}'s
 * {@code Utf8Test}: on decode a string is validated (HARDENING.md § UTF-8)
 * and bytes are not; on encode an overlong string is cut on a character
 * boundary and bytes at the limit (draft -01 § SBE Annotations).
 */
final class TextSbeUtf8Test {

    private static byte[] wire(int[] text, int[] raw) {
        byte[] b = new byte[8 + 16];
        b[0] = 16;              // blockLength
        b[2] = 2;               // templateId
        b[4] = 1;               // schemaId
        for (int i = 0; i < text.length; i++) b[8 + i] = (byte) text[i];
        for (int i = 0; i < raw.length; i++) b[16 + i] = (byte) raw[i];
        return b;
    }

    @Test
    void invalidStringIsRejected() {
        byte[] data = wire(new int[]{0x41, 0x42, 0xFF, 0xFE}, new int[0]);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> TextSbeCodec.unmarshal(data));
        assertEquals("sbe: invalid UTF-8 in string field text", e.getMessage());
    }

    @Test
    void splitCharacterIsRejected() {
        byte[] data = wire(new int[]{0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0xC3}, new int[0]);
        assertThrows(IllegalArgumentException.class, () -> TextSbeCodec.unmarshal(data));
    }

    @Test
    void validStringFillingTheFieldIsAccepted() {
        byte[] data = wire(new int[]{0x63, 0x61, 0x66, 0xC3, 0xA9, 0xE2, 0x82, 0xAC}, new int[0]);
        assertEquals("café€", TextSbeCodec.unmarshal(data).getText());
    }

    @Test
    void invalidBytesFieldIsAccepted() {
        byte[] data = wire(new int[]{0x41, 0x42}, new int[]{0x41, 0x42, 0xFF, 0xFE});
        Text t = TextSbeCodec.unmarshal(data);
        assertEquals("AB", t.getText());
        assertEquals(ByteString.copyFrom(new byte[]{0x41, 0x42, (byte) 0xFF, (byte) 0xFE, 0, 0, 0, 0}), t.getRaw());
    }

    // -- Encode: an overlong value is cut, a string on a character boundary
    // (draft -01 § SBE Annotations, protowire#495, #166). --------------------

    private static byte[] hex(int... b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) out[i] = (byte) b[i];
        return out;
    }

    /** The {@code text} and {@code raw} fields as written. */
    private static byte[][] fields(byte[] wire) {
        return new byte[][]{Arrays.copyOfRange(wire, 8, 16), Arrays.copyOfRange(wire, 16, 24)};
    }

    @Test
    void overlongStringIsCutOnACharacterBoundary() {
        // é is C3 A9, so a cut at 8 would split it after C3.
        byte[] wire = TextSbeCodec.marshal(Text.newBuilder()
                .setText("ABCDEFGé")
                .setRaw(ByteString.copyFromUtf8("ABCDEFGé"))
                .build());
        byte[][] f = fields(wire);
        assertArrayEquals(hex(0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x00), f[0], "string field");
        assertArrayEquals(hex(0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0xC3), f[1], "bytes field");
        // What was written reads back, which a split character would not.
        assertEquals("ABCDEFG", TextSbeCodec.unmarshal(wire).getText());
    }

    @Test
    void cutBacksUpOverAWholeFourByteCharacter() {
        // 😀 is F0 9F 98 80 at bytes 5..8, so the cut at 8 lands three bytes
        // into it and backs up to byte 5.
        byte[] wire = TextSbeCodec.marshal(Text.newBuilder().setText("ABCDE😀").build());
        assertArrayEquals(hex(0x41, 0x42, 0x43, 0x44, 0x45, 0x00, 0x00, 0x00), fields(wire)[0]);
        assertEquals("ABCDE", TextSbeCodec.unmarshal(wire).getText());
    }

    @Test
    void stringThatFillsTheFieldIsNotCut() {
        byte[] wire = TextSbeCodec.marshal(Text.newBuilder().setText("café€").build());
        assertArrayEquals(hex(0x63, 0x61, 0x66, 0xC3, 0xA9, 0xE2, 0x82, 0xAC), fields(wire)[0]);
        assertEquals("café€", TextSbeCodec.unmarshal(wire).getText());
    }
}
