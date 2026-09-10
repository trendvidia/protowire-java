// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.protowire.proto.pxf.Decimal;
import org.protowire.pxf.testproto.BigNums;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code pxf.Decimal} is {@code unscaled × 10^(-scale)}
 * ({@code pxf/bignum.proto}), so a negative scale is trailing zeros (#85).
 * This port's own writers never produce one — {@code setDecimal} writes
 * {@code BigDecimal.scale()}, which callers keep non-negative — so it only
 * surfaces for bytes from another producer; protowire-go's
 * {@code readDecimalStr} multiplies by {@code 10^(-scale)} for it.
 */
class DecimalNegativeScaleTest {

    private static Decimal decimal(long unscaled, int scale) {
        return Decimal.newBuilder()
                .setUnscaled(ByteString.copyFrom(java.math.BigInteger.valueOf(unscaled).toByteArray()))
                .setScale(scale).build();
    }

    @Test
    void negativeScaleIsTrailingZeros() {
        assertEquals("25000", WellKnown.readDecimalStr(decimal(25, -3)));
        assertEquals("-25000", WellKnown.readDecimalStr(decimal(25, -3).toBuilder().setNegative(true).build()));
        assertEquals("25", WellKnown.readDecimalStr(decimal(25, 0)));
        assertEquals("0.025", WellKnown.readDecimalStr(decimal(25, 3)));
        assertEquals("0", WellKnown.readDecimalStr(decimal(0, -3)), "zero stays zero");
    }

    @Test
    void boundedByTheDigitCapOnBothSides() {
        int max = Limits.MAX_NUMERIC_LITERAL_DIGITS;
        assertEquals(2 + max, WellKnown.readDecimalStr(decimal(25, -max)).length());
        PxfException e = assertThrows(PxfException.class, () -> WellKnown.readDecimalStr(decimal(25, -max - 1)));
        assertTrue(e.getMessage().contains("MaxNumericLiteralDigits=" + max), e.getMessage());
    }

    // The literal it writes parses back to the same value: parseDecimal has
    // no negative-scale form, so 25000 reads as unscaled 25000, scale 0.
    @Test
    void writtenLiteralReadsBackToTheSameValue() {
        BigNums msg = BigNums.newBuilder().setDecimal(decimal(25, -3)).build();
        String text = new String(Pxf.marshal(msg), StandardCharsets.UTF_8);
        assertTrue(text.contains("decimal = 25000\n"), text);
        BigNums.Builder back = BigNums.newBuilder();
        Pxf.unmarshal(text.getBytes(StandardCharsets.UTF_8), back);
        assertEquals(0, new BigDecimal("25000").compareTo(new BigDecimal(
                new java.math.BigInteger(1, back.getDecimal().getUnscaled().toByteArray()), back.getDecimal().getScale())));
    }
}
