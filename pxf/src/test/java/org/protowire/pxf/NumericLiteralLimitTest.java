// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;
import org.protowire.proto.pxf.Decimal;
import org.protowire.pxf.testproto.BigNums;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HARDENING.md § Mandatory limits, {@code MaxNumericLiteralDigits} (#81):
 * a PXF numeric literal with more than 4096 digits is rejected before any
 * big-number parser sees it, one with exactly 4096 is accepted — the
 * corpus rows {@code pxf/long-numeric} (5000 digits, reject) and
 * {@code pxf/long-numeric-4096} (accept), on a {@code pxf.BigInt} field
 * where only the cap can reject it. The same constant bounds a
 * {@code pxf.Decimal}'s scale when it is written out.
 */
class NumericLiteralLimitTest {

    private static final int MAX = Limits.MAX_NUMERIC_LITERAL_DIGITS;

    private static BigNums decode(String doc) {
        BigNums.Builder b = BigNums.newBuilder();
        Pxf.unmarshal(doc.getBytes(StandardCharsets.UTF_8), b);
        return b.build();
    }

    private static void assertCapped(String doc, int digits) {
        PxfException e = assertThrows(PxfException.class, () -> decode(doc));
        assertTrue(e.getMessage().contains("numeric literal has " + digits + " digits; MaxNumericLiteralDigits=" + MAX),
                e.getMessage());
    }

    @Test
    void limitIsTheCrossPortDefault() {
        assertEquals(4096, MAX);
    }

    @Test
    void bigIntAtTheBoundIsAccepted() {
        BigNums m = decode("big_int = " + "1".repeat(MAX));
        assertEquals(MAX, WellKnown.readBigInt(m.getBigInt()).toString().length());
    }

    @Test
    void bigIntPastTheBoundIsRejected() {
        assertCapped("big_int = " + "1".repeat(MAX + 1), MAX + 1);
        // The corpus row: 5000 digits.
        assertCapped("big_int = " + "1".repeat(5000), 5000);
    }

    // Digits are what the limit counts: a sign, a point or an exponent
    // marker does not, on either side of the point.
    @Test
    void decimalCountsDigitsOnBothSidesOfThePoint() {
        decode("decimal = -" + "1".repeat(MAX - 10) + "." + "2".repeat(10));
        assertCapped("decimal = -" + "1".repeat(MAX - 10) + "." + "2".repeat(11), MAX + 1);
    }

    // The exponent's digits count as well ("whichever side of the point or
    // the exponent marker"): 4095 nines + e-3 is 4096 digits.
    @Test
    void bigFloatIsUnderTheSameCap() {
        decode("big_float = " + "9".repeat(MAX - 1) + "e-3");
        assertCapped("big_float = " + "9".repeat(MAX) + "e-3", MAX + 1);
    }

    // The bound applies to Decimal.scale on the way out, both signs: writing
    // a Decimal materialises |scale| digits, work an input sets in five
    // bytes (protowire#279).
    @Test
    void decimalScaleIsBoundedWhenWritten() {
        Decimal ok = Decimal.newBuilder().setUnscaled(com.google.protobuf.ByteString.copyFrom(new byte[] {25})).setScale(MAX).build();
        assertEquals(MAX + 2, WellKnown.readDecimalStr(ok).length()); // "0." + 4094 zeros + "25"
        for (int scale : new int[] {MAX + 1, -MAX - 1, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            Decimal bad = Decimal.newBuilder().setScale(scale).build();
            PxfException e = assertThrows(PxfException.class, () -> WellKnown.readDecimalStr(bad), "scale " + scale);
            assertTrue(e.getMessage().contains("MaxNumericLiteralDigits=" + MAX), e.getMessage());
        }
    }
}
