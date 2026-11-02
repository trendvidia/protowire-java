// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.it;

import adversarial.v1.BigNumHolderPxfMeta;
import adversarial.v1.ListHolderPxfMeta;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.DecodeLimits;
import org.protowire.pxf.Parser;
import org.protowire.pxf.PxfException;
import org.protowire.pxf.android.LiteWireReader;
import org.protowire.pxf.android.LiteWireWriter;
import text.v1.Color;
import text.v1.Shapes;
import text.v1.ShapesPxfCodec;
import text.v1.ShapesPxfMeta;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The HARDENING.md limits on the lite tier's two codecs, on generated companions. */
final class LimitsLiteTest {
    private static final DecodeLimits EIGHT = DecodeLimits.defaults().withMaxRepeatedCount(8);

    private static void assertRefused(String doc, DecodeLimits limits, String message) {
        PxfException e = assertThrows(PxfException.class, () -> LiteWireWriter.encode(
            Parser.parse(doc), ListHolderPxfMeta.INSTANCE, limits));
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    @Test
    void aListPastMaxRepeatedCountIsRefused() {
        String nine = "values = [1, 2, 3, 4, 5, 6, 7, 8, 9]";
        assertRefused(nine, EIGHT, "MaxRepeatedCount=8");
        assertDoesNotThrow(() -> LiteWireWriter.encode(Parser.parse(nine), ListHolderPxfMeta.INSTANCE));
    }

    @Test
    void repeatedBindingsCountTogether() {
        assertRefused("values = [1, 2, 3, 4, 5]\nvalues = [6, 7, 8, 9]", EIGHT, "MaxRepeatedCount=8");
    }

    @Test
    void aBigIntPastTheDigitCapIsRefused() {
        String doc = "big_int = " + "1".repeat(4097);
        assertThrows(PxfException.class, () -> LiteWireWriter.encode(Parser.parse(doc), BigNumHolderPxfMeta.INSTANCE));
        assertDoesNotThrow(() -> LiteWireWriter.encode(
            Parser.parse("big_int = " + "1".repeat(4096)), BigNumHolderPxfMeta.INSTANCE));
    }

    @Test
    void aDecimalScalePastTheDigitCapIsRefused() {
        String doc = "decimal = 0." + "0".repeat(4097) + "1";
        assertThrows(PxfException.class, () -> LiteWireWriter.encode(Parser.parse(doc), BigNumHolderPxfMeta.INSTANCE));
    }

    // 0a pxf.Decimal at field 2 of BigNumHolder, scale 2^31-1. Before the bound
    // this ran out of memory writing the digits.
    @Test
    void aHugeDecimalScaleOnTheWireIsRefused() {
        byte[] wire = {0x12, 0x09, 0x0a, 0x01, 0x01, 0x10, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x07};
        PxfException e = assertThrows(PxfException.class,
            () -> LiteWireReader.toAst(wire, BigNumHolderPxfMeta.INSTANCE));
        assertTrue(e.getMessage().contains("exceeds MaxNumericLiteralDigits"), e.getMessage());
    }

    // A repeated enum is packed by default in proto3.
    @Test
    void aPackedEnumReadsBack() {
        Shapes want = Shapes.newBuilder().addColors(Color.COLOR_RED).addColors(Color.COLOR_GREEN).build();
        String text = ShapesPxfCodec.marshal(want);
        assertEquals("colors = [\n  COLOR_RED,\n  COLOR_GREEN\n]\n", text);
        assertEquals(want, ShapesPxfCodec.unmarshal(text));
    }

    @Test
    void anUnknownEnumIsANumber() {
        Shapes want = Shapes.newBuilder().addColorsValue(99).build();
        assertEquals("colors = [\n  99\n]\n", ShapesPxfCodec.marshal(want));
    }

    @Test
    void nonFiniteFloatsRoundTrip() {
        text.v1.Scalars want = text.v1.Scalars.newBuilder()
            .setFDouble(Double.NaN).setFFloat(Float.NEGATIVE_INFINITY).build();
        String doc = text.v1.ScalarsPxfCodec.marshal(want);
        assertEquals("f_double = nan\nf_float = -inf\n", doc);
        assertEquals(want, text.v1.ScalarsPxfCodec.unmarshal(doc));
    }
}
