// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A BigFloat whose value rounded to its precision is past
 * MaxBigFloatExponent has no literal a reader takes, so writing one fails
 * (#186; draft -01 § Mandatory Limits, protowire-go#261). The value is the
 * spec's {@code pb/bigfloat-rounds-past-bound.binpb}: a 54-bit all-ones
 * mantissa at exponent 16330, binary exponent 16384 as it arrives, which at
 * prec 3 rounds to 2^54 and 16385. Both tiers write BigFloats through
 * {@link BigFloats#format}.
 */
class BigFloatsFormatBoundTest {

    private static final BigInteger ALL_ONES_54 = BigInteger.ONE.shiftLeft(54).subtract(BigInteger.ONE);

    @Test
    void aValueThatRoundsPastTheBoundIsNotWritten() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> BigFloats.format(
                ALL_ONES_54, 16330, 3, false, Limits.MAX_NUMERIC_LITERAL_DIGITS, Limits.MAX_BIG_FLOAT_EXPONENT));
        // protowire-go's formatBigFloat, word for word.
        assertEquals("pxf.BigFloat binary exponent 16385 after rounding to prec 3 exceeds MaxBigFloatExponent=16384",
                e.getMessage());
    }

    @Test
    void aValueThatRoundsToTheBoundIsWritten() {
        // 0b111 at the top: rounding to 3 bits carries nothing, so the
        // binary exponent stays 16384.
        BigInteger top3 = BigInteger.valueOf(7).shiftLeft(51);
        String literal = BigFloats.format(top3, 16330, 3, false,
                Limits.MAX_NUMERIC_LITERAL_DIGITS, Limits.MAX_BIG_FLOAT_EXPONENT);
        assertEquals(16384, BigFloats.parse(literal, Limits.MAX_NUMERIC_LITERAL_DIGITS, Limits.MAX_BIG_FLOAT_EXPONENT)
                .exponent() + 3);
    }
}
