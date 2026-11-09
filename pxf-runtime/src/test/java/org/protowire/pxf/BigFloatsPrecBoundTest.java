// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A BigFloat precision past what an int holds, and a zero whose precision is
 * past the digit bound, give protowire-go's verdict and text (#201;
 * protowire-go#229, #274; HARDENING § Arbitrary-precision magnitudes). The
 * suffixes past p13603 are reachable only with MaxNumericLiteralDigits raised,
 * as here; the zero is reachable at the default limits, from a PB value.
 *
 * <p>Each Go text below is what protowire-go v1.17.0's
 * {@code parseBigFloatPrec} and {@code formatBigFloat} return, run on these
 * inputs with the same limits.
 */
class BigFloatsPrecBoundTest {

    /** Wide enough for a p4294967295 suffix: its literal has 1292913988 digits. */
    private static final int RAISED = 1_300_000_000;
    private static final int EXP = Limits.MAX_BIG_FLOAT_EXPONENT;
    private static final int DIGITS = Limits.MAX_NUMERIC_LITERAL_DIGITS;

    private static BigFloats.Value parse(String literal) {
        return BigFloats.parse(literal, RAISED, EXP);
    }

    /**
     * Refused with {@code want}, well inside a second. These used to hang or
     * throw ArithmeticException, so the call runs under a timeout that ends it.
     */
    private static void refused(String literal, String want) {
        long start = System.nanoTime();
        IllegalArgumentException e = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertThrows(IllegalArgumentException.class, () -> parse(literal), literal), literal);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals(want, e.getMessage(), literal);
        assertTrue(ms < 1000, literal + " took " + ms + " ms");
    }

    @Test
    void aSuffixPastAnIntIsRefusedAsGoRefusesIt() {
        Map.of(
                "1p2147483648", "1: precision p2147483648",
                "1.5p4294967295", "1.5: precision p4294967295",
                "1p4294967295", "1: precision p4294967295",
                "-1024p2147483648", "-1024: precision p2147483648",
                "1e5000p4294967295", "1e5000: precision p4294967295",
                "1e-5000p4294967295", "1e-5000: precision p4294967295")
            .forEach((literal, head) -> refused(literal,
                "big float literal " + head + " exceeds the widest mantissa big.Float can hold, 2147483647 bits"));
    }

    @Test
    void aZeroTakesAnySuffixADigitBoundAdmits() {
        assertEquals(new BigFloats.Value(BigInteger.ZERO, 0, (int) 2147483648L, false), parse("0p2147483648"));
        assertEquals(new BigFloats.Value(BigInteger.ZERO, 0, -1, false), parse("0p4294967295"));
    }

    /** The wire carries exp - prec as an int32; Go refuses a value whose exp - prec is below it. */
    @Test
    void aValueBelowTheWiresExponentRangeIsRefused() {
        for (String literal : new String[] {"1e-300p2147483647", "1e-6000p2147483647", "1e-3000p2147480000", "1e-3000p2147483000"}) {
            String number = literal.substring(0, literal.indexOf('p'));
            refused(literal, "big float literal " + number + " is below the wire's exponent range");
        }
    }

    @Test
    void aValuePastBigFloatsRangeIsRefused() {
        refused("1e1000000000p4294967295", "big float literal 1e1000000000 is above big.Float's range");
        refused("1e-1000000000p2147483647", "big float literal 1e-1000000000 is below big.Float's range");
    }

    /**
     * Divergence, pinned: Go accepts these, holding a mantissa of 2^31-1 bits.
     * Rounding one to prec bits needs an integer wider than a Java BigInteger
     * holds: the value shifted to prec bits, and one more for a carry. So
     * Java refuses them. Reachable only with MaxNumericLiteralDigits raised
     * past 646 million.
     */
    @Test
    void aMantissaWiderThanABigIntegerIsRefused() {
        refused("1p2147483647", "big float literal 1: precision p2147483647 needs a 2147483648-bit integer,"
                + " wider than a Java BigInteger holds (2147483647 bits)");
        refused("0.5p2147483647", "big float literal 0.5: precision p2147483647 needs a 2147483651-bit integer,"
                + " wider than a Java BigInteger holds (2147483647 bits)");
    }

    // -- writing --------------------------------------------------------------

    @Test
    void aZeroPastTheDigitBoundIsNotWritten() {
        assertEquals("0p13603", BigFloats.format(BigInteger.ZERO, 0, 13603, false, DIGITS, EXP));
        for (boolean negative : new boolean[] {false, true}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> BigFloats.format(BigInteger.ZERO, 0, 13604, negative, DIGITS, EXP));
            assertEquals("pxf.BigFloat prec 13604 bits renders to 4097 digits; MaxNumericLiteralDigits=4096", e.getMessage());
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BigFloats.format(BigInteger.ZERO, 0, -1, false, DIGITS, EXP));
        assertEquals("pxf.BigFloat prec 4294967295 bits renders to 1292913988 digits; MaxNumericLiteralDigits=4096", e.getMessage());
    }

    /** Go writes the rounded bound before the digit bound, so a value past both says the first. */
    @Test
    void theRoundedBoundComesBeforeTheDigitBound() {
        BigInteger allOnes54 = BigInteger.ONE.shiftLeft(54).subtract(BigInteger.ONE);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BigFloats.format(allOnes54, 16330, 3, false, 1, EXP));
        assertEquals("pxf.BigFloat binary exponent 16385 after rounding to prec 3 exceeds MaxBigFloatExponent=16384", e.getMessage());
    }

    /** Divergence, pinned: Go renders these; their digits need a wider integer than a Java BigInteger holds. */
    @Test
    void aPrecisionPastAnIntIsNotWrittenOrNormalized() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BigFloats.format(BigInteger.ONE, 0, -1, false, RAISED, EXP));
        assertEquals("pxf.BigFloat prec 4294967295 bits renders to 1292913988 digits, and computing them needs a wider"
                + " integer than a Java BigInteger holds (2147483647 bits)", e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> BigFloats.normalize(BigInteger.ONE, 0, -1, false, RAISED, EXP));
        assertEquals("pxf.BigFloat prec 4294967295 needs a 4294967295-bit integer, wider than a Java BigInteger holds (2147483647 bits)",
                e.getMessage());
        // A zero has no mantissa to widen.
        assertEquals(new BigFloats.Value(BigInteger.ZERO, 0, -1, false),
                BigFloats.normalize(BigInteger.ZERO, 0, -1, false, RAISED, EXP));
    }
}
