// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import java.math.BigInteger;

/**
 * The text form of a {@code pxf.BigFloat} (draft -01 § PXF Annotations,
 * protowire#393, #406, #410), shared by the full and the lite tier. A
 * BigFloat is a binary floating-point number: the unsigned integer
 * {@code mantissa} × 2^{@code exponent}, at {@code prec} bits.
 *
 * <p><b>Writing:</b> N = (decimal digits of 2^prec) + 1 significant digits,
 * round half even, in C's {@code %g} form, then the suffix {@code p<prec>}.
 * A mantissa wider than prec is the value rounded to prec. A zero is
 * {@code 0p<prec>} or {@code -0p<prec>}; only a prec-0 zero is {@code 0}.
 *
 * <p><b>Reading:</b> a suffixed literal is read at exactly the suffix's
 * precision, an unsuffixed one at {@value #DEFAULT_PREC} bits, round half
 * even. The stored mantissa is the significand as a prec-bit integer, and
 * the exponent is the binary exponent minus prec.
 *
 * <p>All of it is exact {@link BigInteger} arithmetic. The bounds come first,
 * because {@code 10^exponent} in a thirteen-byte literal is otherwise a
 * denial of service: a literal or message past {@code MaxBigFloatExponent}
 * is rejected before anything is computed from it (protowire#281).
 */
public final class BigFloats {
    private BigFloats() {}

    /** The precision a literal without a suffix is read at. */
    public static final int DEFAULT_PREC = 256;

    /**
     * A BigFloat's fields as the message stores them.
     *
     * @param mantissa unsigned; zero for a zero value
     * @param exponent the wire exponent
     * @param prec     precision in bits; 0 only for a zero written with no suffix
     * @param negative the sign, kept for a zero
     */
    public record Value(BigInteger mantissa, int exponent, int prec, boolean negative) {}

    /** floor(log10(2) × 2^128). */
    private static final BigInteger LOG10_2_Q128 = new BigInteger("4d104d427de7fbcc47c4acd605be48bc", 16);

    /**
     * The decimal digits of 2^p, computed in 128-bit fixed point rather than
     * from 2^p: {@code p} comes off the wire or out of a document, and a
     * double is one digit high at p = 146964308.
     */
    static int pow2Digits(long p) {
        return BigInteger.valueOf(p).multiply(LOG10_2_Q128).shiftRight(128).intValueExact() + 1;
    }

    /** N, the significant digits a prec-bit value is written with. */
    private static long digitsFor(long prec) {
        return (long) pow2Digits(prec) + 1;
    }

    /**
     * What {@code MaxNumericLiteralDigits} counts: the significant digits of
     * a literal's mantissa, either side of the point, leading zeros excluded
     * (draft -01 § Mandatory Limits, protowire#410). An exponent and a
     * precision suffix are bounded on their own, so the count stops at
     * either marker.
     */
    public static int countDigits(String s) {
        int n = 0;
        boolean leading = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'e' || c == 'E' || c == 'p') break;
            if (c < '0' || c > '9' || (leading && c == '0')) continue;
            leading = false;
            n++;
        }
        return n;
    }

    private static String clip(String s) {
        return s.length() <= 24 ? s : s.substring(0, 24) + "…";
    }

    // -- reading ------------------------------------------------------------

    /**
     * Reads a literal that arrives as one string, a {@code (pxf.default)}.
     * A document's literal arrives lexed: see
     * {@link #parse(String, String, int, int)}.
     *
     * @throws IllegalArgumentException on a literal this type cannot hold
     */
    public static Value parse(String literal, int maxDigits, int maxExponent) {
        int p = literal.indexOf('p');
        if (p < 0) return parse(literal, "", maxDigits, maxExponent);
        if (p == literal.length() - 1) {
            throw new IllegalArgumentException("big float literal " + clip(literal) + ": precision suffix p is not a positive bit count");
        }
        return parse(literal.substring(0, p), literal.substring(p + 1), maxDigits, maxExponent);
    }

    /**
     * Reads a number at the precision its suffix's digits state, or at
     * {@value #DEFAULT_PREC} bits when {@code precDigits} is empty. The suffix
     * is checked before anything is allocated for it.
     *
     * @throws IllegalArgumentException on a literal this type cannot hold
     */
    public static Value parse(String number, String precDigits, int maxDigits, int maxExponent) {
        boolean suffixed = !precDigits.isEmpty();
        int prec = DEFAULT_PREC;
        if (suffixed) {
            long p = precDigits.length() <= 10 ? Long.parseLong(precDigits) : 0;
            if (p == 0 || p > 0xFFFFFFFFL) {
                throw new IllegalArgumentException("big float literal " + clip(number) + "p" + clip(precDigits)
                        + ": precision suffix p" + clip(precDigits) + " is not a positive bit count");
            }
            long n = digitsFor(p);
            if (n > maxDigits) {
                throw new IllegalArgumentException("big float literal " + clip(number) + "p" + p + ": precision p" + p
                        + " renders to " + n + " digits; MaxNumericLiteralDigits=" + maxDigits);
            }
            prec = (int) p;
        }
        int nd = countDigits(number);
        if (nd > maxDigits) {
            throw new IllegalArgumentException("numeric literal has " + nd + " digits; MaxNumericLiteralDigits=" + maxDigits);
        }

        boolean negative = number.startsWith("-");
        String s = negative ? number.substring(1) : number;
        int mark = Math.max(s.indexOf('e'), s.indexOf('E'));
        String mantissa = mark < 0 ? s : s.substring(0, mark);
        long exp10 = mark < 0 ? 0 : parseExponent(s.substring(mark + 1));
        int dot = mantissa.indexOf('.');
        String digits = dot < 0 ? mantissa : mantissa.substring(0, dot) + mantissa.substring(dot + 1);
        if (dot >= 0) exp10 -= mantissa.length() - dot - 1;

        BigInteger d;
        try {
            d = new BigInteger(digits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid big float: " + clip(number));
        }
        if (d.signum() == 0) return new Value(BigInteger.ZERO, 0, suffixed ? prec : 0, negative);

        // value = d × 10^exp10, so 10^(top-1) <= value < 10^top. A bound of
        // log10(2) × maxExponent + 2 decades cannot reject a value within
        // MaxBigFloatExponent, and it keeps 10^exp10 from being computed for
        // the exponent of a thirteen-byte literal. The exact check follows
        // the rounding, because a literal can lie just past the bound and
        // still read back (protowire#410).
        long top = exp10 + d.toString().length();
        long decades = (long) (maxExponent * 0.30103) + 2;
        if (top - 1 > decades || top < -decades) {
            throw new IllegalArgumentException("big float literal " + clip(number) + " is past MaxBigFloatExponent=" + maxExponent);
        }

        BigInteger num = exp10 >= 0 ? d.multiply(BigInteger.TEN.pow((int) exp10)) : d;
        BigInteger den = exp10 >= 0 ? BigInteger.ONE : BigInteger.TEN.pow((int) -exp10);
        return roundToPrec(num, den, prec, negative, number, maxExponent);
    }

    /** A decimal exponent's value, saturated well past any bound: the literal's digits are the attacker's. */
    private static long parseExponent(String s) {
        boolean neg = s.startsWith("-");
        String t = neg || s.startsWith("+") ? s.substring(1) : s;
        long v = t.isEmpty() ? 0 : t.length() > 18 ? Long.MAX_VALUE / 4 : Long.parseLong(t);
        return neg ? -v : v;
    }

    /** num/den at exactly {@code prec} significant bits, round half even. */
    private static Value roundToPrec(BigInteger num, BigInteger den, int prec, boolean negative, String literal, int maxExponent) {
        int e = num.bitLength() - den.bitLength() - prec;
        BigInteger[] qr;
        BigInteger d;
        while (true) { // 2^(prec-1) <= num/den / 2^e < 2^prec, in at most two steps
            d = e >= 0 ? den.shiftLeft(e) : den;
            qr = (e >= 0 ? num : num.shiftLeft(-e)).divideAndRemainder(d);
            int bits = qr[0].bitLength();
            if (bits == prec) break;
            e += bits > prec ? 1 : -1;
        }
        BigInteger m = roundHalfEven(qr[0], qr[1], d);
        if (m.bitLength() > prec) { // rounded up past the significand, to a power of two
            m = m.shiftRight(1);
            e++;
        }
        long binary = (long) e + prec;
        if (binary > maxExponent || binary < -maxExponent) {
            throw new IllegalArgumentException("big float literal " + clip(literal) + " has binary exponent " + binary
                    + ", and MaxBigFloatExponent=" + maxExponent + " bounds its magnitude");
        }
        return new Value(m, e, prec, negative);
    }

    private static BigInteger roundHalfEven(BigInteger q, BigInteger rem, BigInteger den) {
        int c = rem.shiftLeft(1).compareTo(den);
        return c > 0 || (c == 0 && q.testBit(0)) ? q.add(BigInteger.ONE) : q;
    }

    // -- writing ------------------------------------------------------------

    /**
     * Writes a message's fields as a literal, its suffix included.
     *
     * @param prec the wire's uint32, read as unsigned
     * @throws IllegalArgumentException on a value a decoder would refuse, so
     *     it is never written out as a literal nobody can read
     */
    public static String format(BigInteger mantissa, int exponent, int prec, boolean negative, int maxDigits, int maxExponent) {
        String sign = negative ? "-" : "";
        // A precision of zero is zero whatever the other fields say.
        if (prec == 0) return sign + "0";
        long p = Integer.toUnsignedLong(prec);
        if (mantissa.signum() == 0) return sign + "0p" + p;

        long binary = (long) exponent + mantissa.bitLength();
        if (binary > maxExponent || binary < -maxExponent) {
            throw new IllegalArgumentException("pxf.BigFloat binary exponent " + binary + " exceeds MaxBigFloatExponent=" + maxExponent);
        }
        long n = digitsFor(p);
        if (n > maxDigits) {
            throw new IllegalArgumentException("pxf.BigFloat prec " + p + " bits renders to " + n
                    + " digits; MaxNumericLiteralDigits=" + maxDigits);
        }

        // A mantissa wider than prec is the value rounded to prec (#410).
        BigInteger m = mantissa;
        long e = exponent;
        int excess = m.bitLength() - (int) p;
        if (excess > 0) {
            BigInteger q = m.shiftRight(excess);
            BigInteger rem = m.subtract(q.shiftLeft(excess));
            m = roundHalfEven(q, rem, BigInteger.ONE.shiftLeft(excess));
            e += excess;
        }
        BigInteger num = e >= 0 ? m.shiftLeft((int) e) : m;
        BigInteger den = e >= 0 ? BigInteger.ONE : BigInteger.ONE.shiftLeft((int) -e);

        // k: 10^k <= value < 10^(k+1).
        int k = (int) Math.floor((m.bitLength() + e - 1) * 0.30103);
        while (compareToPow10(num, den, k + 1) >= 0) k++;
        while (compareToPow10(num, den, k) < 0) k--;

        int shift = (int) n - 1 - k; // value × 10^shift has n integer digits
        BigInteger sn = shift >= 0 ? num.multiply(BigInteger.TEN.pow(shift)) : num;
        BigInteger sd = shift >= 0 ? den : den.multiply(BigInteger.TEN.pow(-shift));
        BigInteger[] qr = sn.divideAndRemainder(sd);
        BigInteger q = roundHalfEven(qr[0], qr[1], sd);
        String digits = q.toString();
        if (digits.length() > n) { // rounded up to 10^n
            digits = digits.substring(0, (int) n);
            k++;
        }
        digits = stripTrailingZeros(digits);

        StringBuilder sb = new StringBuilder(sign);
        if (k < -4 || k >= n) {
            sb.append(digits.charAt(0));
            if (digits.length() > 1) sb.append('.').append(digits, 1, digits.length());
            sb.append('e').append(k < 0 ? '-' : '+');
            int a = Math.abs(k);
            if (a < 10) sb.append('0');
            sb.append(a);
        } else if (k >= 0) {
            int ip = k + 1;
            if (digits.length() <= ip) {
                sb.append(digits).append("0".repeat(ip - digits.length()));
            } else {
                sb.append(digits, 0, ip).append('.').append(digits, ip, digits.length());
            }
        } else {
            sb.append("0.").append("0".repeat(-k - 1)).append(digits);
        }
        return sb.append('p').append(p).toString();
    }

    /** Compares num/den with 10^k. */
    private static int compareToPow10(BigInteger num, BigInteger den, int k) {
        return k >= 0
                ? num.compareTo(den.multiply(BigInteger.TEN.pow(k)))
                : num.multiply(BigInteger.TEN.pow(-k)).compareTo(den);
    }

    private static String stripTrailingZeros(String s) {
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '0') end--;
        return s.substring(0, end);
    }
}
