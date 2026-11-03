// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe.it;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.protowire.sbe.testproto.Text;
import org.protowire.sbe.testproto.TextSbeCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The lite tier's UTF-8 rules for an {@code (sbe.length)} field, through the
 * codegen-emitted {@link TextSbeCodec}. The same cases as {@code :sbe}'s
 * {@code Utf8Test}: on decode a string is validated (HARDENING.md § UTF-8)
 * and bytes are not.
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
}
