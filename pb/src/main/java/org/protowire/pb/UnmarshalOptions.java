// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pb;

import org.protowire.check.CheckException;
import org.protowire.check.Validator;

/**
 * The per-call decoder limits of protowire's {@code docs/HARDENING.md}
 * § Mandatory limits for {@link Pb#unmarshal(byte[], Object, UnmarshalOptions)}
 * (#79). {@link #defaults()} is the cross-port constants; a caller lowers
 * one with the {@code with*} methods (a value must be positive). This
 * module carries its own copy of the record because it has no dependency
 * on {@code :pxf-runtime}.
 *
 * @param maxMessageSize          {@code MaxMessageSize}: the total input to
 *     one decode call, checked before the first byte is read
 * @param maxNestingDepth         {@code MaxNestingDepth}: submessage / map-entry
 *     nesting, counted as descents from a root at depth 0
 * @param maxNumericLiteralDigits {@code MaxNumericLiteralDigits}: the bound on
 *     a {@code pxf.Decimal}'s {@code scale}, both signs
 * @param maxRepeatedCount        {@code MaxRepeatedCount}: the element count of
 *     any repeated or map field
 * @param maxBigFloatExponent     {@code MaxBigFloatExponent}: the magnitude of
 *     a {@code pxf.BigFloat} value's binary exponent
 * @param validator               run on the bound object after a successful
 *     decode, as protowire-go's {@code UnmarshalOptions.Validator} (#205); a
 *     report with violations fails the decode with a {@link CheckException};
 *     nullable
 */
public record UnmarshalOptions(int maxMessageSize, int maxNestingDepth, int maxNumericLiteralDigits, int maxRepeatedCount, int maxBigFloatExponent,
                               Validator validator) {

    /** As the six-argument form with no {@link Validator}. */
    public UnmarshalOptions(int maxMessageSize, int maxNestingDepth, int maxNumericLiteralDigits, int maxRepeatedCount, int maxBigFloatExponent) {
        this(maxMessageSize, maxNestingDepth, maxNumericLiteralDigits, maxRepeatedCount, maxBigFloatExponent, null);
    }

    /** As the five-argument form with the default {@code MaxBigFloatExponent}. */
    public UnmarshalOptions(int maxMessageSize, int maxNestingDepth, int maxNumericLiteralDigits, int maxRepeatedCount) {
        this(maxMessageSize, maxNestingDepth, maxNumericLiteralDigits, maxRepeatedCount, Pb.MAX_BIG_FLOAT_EXPONENT);
    }

    public UnmarshalOptions {
        requirePositive("MaxMessageSize", maxMessageSize);
        requirePositive("MaxNestingDepth", maxNestingDepth);
        requirePositive("MaxNumericLiteralDigits", maxNumericLiteralDigits);
        requirePositive("MaxRepeatedCount", maxRepeatedCount);
        requirePositive("MaxBigFloatExponent", maxBigFloatExponent);
    }

    private static void requirePositive(String name, int v) {
        if (v <= 0) throw new IllegalArgumentException(name + " must be positive, got " + v);
    }

    private static final UnmarshalOptions DEFAULTS = new UnmarshalOptions(
            Pb.MAX_MESSAGE_SIZE, Pb.MAX_NESTING_DEPTH, Pb.MAX_NUMERIC_LITERAL_DIGITS, Pb.MAX_REPEATED_COUNT, Pb.MAX_BIG_FLOAT_EXPONENT);

    public static UnmarshalOptions defaults() { return DEFAULTS; }

    public UnmarshalOptions withMaxMessageSize(int v)          { return new UnmarshalOptions(v, maxNestingDepth, maxNumericLiteralDigits, maxRepeatedCount, maxBigFloatExponent, validator); }
    public UnmarshalOptions withMaxNestingDepth(int v)         { return new UnmarshalOptions(maxMessageSize, v, maxNumericLiteralDigits, maxRepeatedCount, maxBigFloatExponent, validator); }
    public UnmarshalOptions withMaxNumericLiteralDigits(int v) { return new UnmarshalOptions(maxMessageSize, maxNestingDepth, v, maxRepeatedCount, maxBigFloatExponent, validator); }
    public UnmarshalOptions withMaxRepeatedCount(int v)        { return new UnmarshalOptions(maxMessageSize, maxNestingDepth, maxNumericLiteralDigits, v, maxBigFloatExponent, validator); }

    public UnmarshalOptions withMaxBigFloatExponent(int v)     { return new UnmarshalOptions(maxMessageSize, maxNestingDepth, maxNumericLiteralDigits, maxRepeatedCount, v, validator); }

    /** The same limits with {@code v} run after each decode; null for none. */
    public UnmarshalOptions withValidator(Validator v)         { return new UnmarshalOptions(maxMessageSize, maxNestingDepth, maxNumericLiteralDigits, maxRepeatedCount, maxBigFloatExponent, v); }

    /**
     * Sets one limit by its HARDENING.md name. {@code MaxBytesLiteralLength}
     * is accepted and ignored: it is about PXF literals and has no PB
     * counterpart.
     */
    public UnmarshalOptions with(String name, int value) {
        return switch (name) {
            case "MaxMessageSize" -> withMaxMessageSize(value);
            case "MaxNestingDepth" -> withMaxNestingDepth(value);
            case "MaxNumericLiteralDigits" -> withMaxNumericLiteralDigits(value);
            case "MaxRepeatedCount" -> withMaxRepeatedCount(value);
            case "MaxBigFloatExponent" -> withMaxBigFloatExponent(value);
            case "MaxBytesLiteralLength" -> this;
            default -> throw new IllegalArgumentException("unknown limit \"" + name + "\"");
        };
    }
}
