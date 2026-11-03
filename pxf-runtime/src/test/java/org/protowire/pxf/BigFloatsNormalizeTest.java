// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link BigFloats#normalize} is the form the PXF decoder reads a BigFloat
 * in: writing a message's fields as a literal and reading the literal back
 * gives the same fields (protowire-java#159). That is two independent paths
 * to one answer, the decimal one through {@code format} and {@code parse}.
 */
class BigFloatsNormalizeTest {

    private static final int DIGITS = Limits.MAX_NUMERIC_LITERAL_DIGITS;
    private static final int EXP = Limits.MAX_BIG_FLOAT_EXPONENT;

    private static BigFloats.Value viaText(BigInteger m, int exp, int prec, boolean neg) {
        return BigFloats.parse(BigFloats.format(m, exp, prec, neg, DIGITS, EXP), DIGITS, EXP);
    }

    @Test
    void agreesWithTheDecoderOnNarrowAndWideMantissas() {
        Random r = new Random(159);
        for (int i = 0; i < 3000; i++) {
            int prec = 1 + r.nextInt(400);
            int bits = 1 + r.nextInt(prec + 200); // narrower, as wide as, and wider than prec
            BigInteger m = new BigInteger(bits, r).setBit(bits - 1);
            int exp = r.nextInt(8000) - 4000;
            boolean neg = r.nextBoolean();
            BigFloats.Value v = BigFloats.normalize(m, exp, prec, neg, DIGITS, EXP);
            assertEquals(viaText(m, exp, prec, neg), v, m.toString(16) + " × 2^" + exp + " at " + prec);
            assertEquals(prec, v.mantissa().bitLength(), "the mantissa is exactly prec bits");
            assertEquals(v, BigFloats.normalize(v.mantissa(), v.exponent(), prec, neg, DIGITS, EXP), "idempotent");
        }
    }

    @Test
    void aNarrowMantissaIsWidenedWithoutChangingTheValue() {
        BigFloats.Value v = BigFloats.normalize(BigInteger.valueOf(3), -1, 64, false, DIGITS, EXP); // 1.5
        assertEquals(new BigFloats.Value(new BigInteger("c000000000000000", 16), -63, 64, false), v);
    }

    @Test
    void roundingCarriesToTheNextPowerOfTwo() {
        // 0xFF at one bit rounds to 0x100: mantissa 1, exponent 8.
        assertEquals(new BigFloats.Value(BigInteger.ONE, 8, 1, false),
                BigFloats.normalize(BigInteger.valueOf(0xff), 0, 1, false, DIGITS, EXP));
        assertEquals(viaText(BigInteger.valueOf(0xff), 0, 1, false),
                BigFloats.normalize(BigInteger.valueOf(0xff), 0, 1, false, DIGITS, EXP));
    }

    @Test
    void zeros() {
        // A zero keeps its prec and sign and has no exponent; prec 0 is zero whatever the mantissa.
        assertEquals(new BigFloats.Value(BigInteger.ZERO, 0, 53, true),
                BigFloats.normalize(BigInteger.ZERO, 7, 53, true, DIGITS, EXP));
        assertEquals(new BigFloats.Value(BigInteger.ZERO, 0, 0, true),
                BigFloats.normalize(BigInteger.valueOf(3), -1, 0, true, DIGITS, EXP));
        assertEquals(viaText(BigInteger.ZERO, 7, 53, true), BigFloats.normalize(BigInteger.ZERO, 7, 53, true, DIGITS, EXP));
    }

    @Test
    void refusesWhatADecoderRefuses() {
        assertThrows(IllegalArgumentException.class,
                () -> BigFloats.normalize(BigInteger.ONE, 0, 13604, false, DIGITS, EXP)); // prec past the digit cap
        assertThrows(IllegalArgumentException.class,
                () -> BigFloats.normalize(BigInteger.ONE, 0, -1, false, DIGITS, EXP)); // 2^32-1
        BigFloats.normalize(BigInteger.ONE, 0, 13603, false, DIGITS, EXP);
        // 0xFF × 2^(bound-8) is inside the bound as written and one past it rounded.
        assertThrows(IllegalArgumentException.class,
                () -> BigFloats.normalize(BigInteger.valueOf(0xff), EXP - 8, 1, false, DIGITS, EXP));
        BigFloats.normalize(BigInteger.valueOf(0xff), EXP - 8, 8, false, DIGITS, EXP);
    }
}
