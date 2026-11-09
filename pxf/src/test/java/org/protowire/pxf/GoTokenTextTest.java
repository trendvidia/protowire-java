// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.AllTypes;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Errors the spec corpus does not reach, in protowire-go's words: a key that
 * cannot name a field, and a character no token starts with. Both the decoder
 * and the parser, which the lite tier reads through, say what Go's
 * {@code Unmarshal} and {@code Parse} say. The texts are protowire-go
 * v1.17.0's, on {@code test.v1.AllTypes}; {@link GoVerdictsTest} holds the
 * corpus to Go's table.
 */
class GoTokenTextTest {

    private static final Map<String, String> GO = new LinkedHashMap<>();
    static {
        GO.put("1 = 2",
                "1:1: field assignment with '=' requires an identifier or string key, got integer (\"1\"); use ':' for map entries");
        GO.put("true { }", "1:1: submessage block requires an identifier or string key, got bool (\"true\")");
        GO.put("string_field = μ", "1:16: unexpected character 'μ'");
        GO.put("μ = 1", "1:1: expected identifier, string, integer, or bool, got unexpected character 'μ'");
        GO.put("string_field = \u0001", "1:16: unexpected character '\\x01'");
    }

    @Test
    void theDecoderAndTheParserSayWhatGoSays() {
        GO.forEach((doc, want) -> {
            byte[] b = doc.getBytes(StandardCharsets.UTF_8);
            assertEquals(want, assertThrows(PxfException.class, () -> Pxf.unmarshal(b, AllTypes.getDescriptor())).getMessage(), doc);
            assertEquals(want, assertThrows(PxfException.class, () -> Pxf.parse(b)).getMessage(), doc);
        });
    }

    @Test
    void aByteThatIsNotUtf8IsNamedInHex() {
        byte[] b = {(byte) 0xff, ' ', '=', ' ', '1'};
        String want = "1:1: expected identifier, string, integer, or bool, got unexpected byte 0xff";
        assertEquals(want, assertThrows(PxfException.class, () -> Pxf.unmarshal(b, AllTypes.getDescriptor())).getMessage());
        assertEquals(want, assertThrows(PxfException.class, () -> Pxf.parse(b)).getMessage());
    }
}
