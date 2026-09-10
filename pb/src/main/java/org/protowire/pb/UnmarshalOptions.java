// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pb;

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
 */
public record UnmarshalOptions(int maxMessageSize, int maxNestingDepth, int maxNumericLiteralDigits, int maxRepeatedCount) {

    public UnmarshalOptions {
        requirePositive("MaxMessageSize", maxMessageSize);
        requirePositive("MaxNestingDepth", maxNestingDepth);
        requirePositive("MaxNumericLiteralDigits", maxNumericLiteralDigits);
        requirePositive("MaxRepeatedCount", maxRepeatedCount);
    }

    private static void requirePositive(String name, int v) {
        if (v <= 0) throw new IllegalArgumentException(name + " must be positive, got " + v);
    }

    private static final UnmarshalOptions DEFAULTS = new UnmarshalOptions(
            Pb.MAX_MESSAGE_SIZE, Pb.MAX_NESTING_DEPTH, Pb.MAX_NUMERIC_LITERAL_DIGITS, Pb.MAX_REPEATED_COUNT);

    public static UnmarshalOptions defaults() { return DEFAULTS; }

    public UnmarshalOptions withMaxMessageSize(int v)          { return new UnmarshalOptions(v, maxNestingDepth, maxNumericLiteralDigits, maxRepeatedCount); }
    public UnmarshalOptions withMaxNestingDepth(int v)         { return new UnmarshalOptions(maxMessageSize, v, maxNumericLiteralDigits, maxRepeatedCount); }
    public UnmarshalOptions withMaxNumericLiteralDigits(int v) { return new UnmarshalOptions(maxMessageSize, maxNestingDepth, v, maxRepeatedCount); }
    public UnmarshalOptions withMaxRepeatedCount(int v)        { return new UnmarshalOptions(maxMessageSize, maxNestingDepth, maxNumericLiteralDigits, v); }

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
            case "MaxBytesLiteralLength" -> this;
            default -> throw new IllegalArgumentException("unknown limit \"" + name + "\"");
        };
    }
}
