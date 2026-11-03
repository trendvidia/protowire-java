// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.it;

import org.junit.jupiter.api.Test;
import org.protowire.pxf.DecodeLimits;
import org.protowire.pxf.Parser;
import org.protowire.pxf.android.LiteWireWriter;
import text.v1.ShapesPxfMeta;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LiteWireWriter writes map entries in key order, as protowire-go's
 * deterministic writer does, so the write gate can compare its bytes with
 * Go's (protowire#509). Each document lists its keys out of order; each
 * expected value is the golden protowire-go wrote for the same document
 * (protowire testdata/write/golden/).
 */
final class MapOrderLiteTest {

    private static String encode(String pxf) {
        byte[] out = LiteWireWriter.encode(
            Parser.parse(pxf.getBytes(StandardCharsets.UTF_8), DecodeLimits.defaults()),
            ShapesPxfMeta.INSTANCE, DecodeLimits.defaults());
        return HexFormat.of().formatHex(out);
    }

    @Test
    void integerKeysGoInNumericOrder() {
        // -1, 0, 9, 10: by value, so 9 before 10, and negative first.
        assertEquals(
            "521008ffffffffffffffffff0112036e65675208080012047a65726f5208080912046e696e655207080a120374656e",
            encode("by_int = {\n  10: \"ten\"\n  9: \"nine\"\n  -1: \"neg\"\n  0: \"zero\"\n}\n"));
    }

    @Test
    void stringKeysGoInUtf8ByteOrder() {
        // U+FF61 (EF BD A1) before U+1F600 (F0 9F 98 80): UTF-8 byte order.
        // Java's String order, UTF-16, would put the emoji (D83D ...) first.
        assertEquals(
            "4a050a001201354a070a0231321201364a060a01391201374a060a01611201324a060a0162120131"
                + "4a080a03efbda11201344a090a04f09f9880120133",
            encode("by_string = {\n  \"b\": \"1\"\n  \"a\": \"2\"\n  \"😀\": \"3\"\n  \"｡\": \"4\"\n"
                + "  \"\": \"5\"\n  \"12\": \"6\"\n  \"9\": \"7\"\n}\n"));
    }

    @Test
    void falseGoesBeforeTrue() {
        assertEquals("5a0508001201665a050801120174",
            encode("by_bool = {\n  true: \"t\"\n  false: \"f\"\n}\n"));
    }

    @Test
    void messageValuedMapsAreSortedToo() {
        assertEquals("620c0a017812070a056669727374620d0a017912080a067365636f6e64",
            encode("by_item = {\n  y: {\n    name = \"second\"\n  }\n  x: {\n    name = \"first\"\n  }\n}\n"));
    }
}
