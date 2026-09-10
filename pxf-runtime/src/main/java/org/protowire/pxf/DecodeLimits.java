// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

/**
 * The per-call decoder limits of draft -01 § Mandatory Limits and
 * protowire's {@code docs/HARDENING.md} § Mandatory limits: every limit
 * but {@code MaxVarintBytes} is "configurable per call by the calling
 * application". {@link #defaults()} is the cross-port constants of
 * {@link Limits}; a caller lowers one with the {@code with*} methods (a
 * value must be positive). Schema input — a {@code (pxf.default)}
 * literal — stays under the constants: it is the schema author's, not
 * the document's.
 *
 * @param maxMessageSize          {@code MaxMessageSize}: the total input to
 *     one decode or parse call, checked before the first byte is read
 * @param maxNestingDepth         {@code MaxNestingDepth}: block / list
 *     nesting, counted as descents from a root at depth 0
 * @param maxNumericLiteralDigits {@code MaxNumericLiteralDigits}: the digit
 *     count of any numeric literal before a big-number parser sees it
 * @param maxBytesLiteralLength   {@code MaxBytesLiteralLength}: the decoded
 *     length of any {@code b"…"} literal, checked from its length before
 *     decoding
 * @param maxRepeatedCount        {@code MaxRepeatedCount}: the element count
 *     of any repeated or map field
 */
public record DecodeLimits(
        int maxMessageSize,
        int maxNestingDepth,
        int maxNumericLiteralDigits,
        int maxBytesLiteralLength,
        int maxRepeatedCount) {

    public DecodeLimits {
        requirePositive("MaxMessageSize", maxMessageSize);
        requirePositive("MaxNestingDepth", maxNestingDepth);
        requirePositive("MaxNumericLiteralDigits", maxNumericLiteralDigits);
        requirePositive("MaxBytesLiteralLength", maxBytesLiteralLength);
        requirePositive("MaxRepeatedCount", maxRepeatedCount);
    }

    private static void requirePositive(String name, int v) {
        if (v <= 0) throw new IllegalArgumentException(name + " must be positive, got " + v);
    }

    private static final DecodeLimits DEFAULTS = new DecodeLimits(
            Limits.MAX_MESSAGE_SIZE, Limits.MAX_NESTING_DEPTH, Limits.MAX_NUMERIC_LITERAL_DIGITS,
            Limits.MAX_BYTES_LITERAL_LENGTH, Limits.MAX_REPEATED_COUNT);

    /** The cross-port defaults of {@link Limits}. */
    public static DecodeLimits defaults() { return DEFAULTS; }

    public DecodeLimits withMaxMessageSize(int v)          { return new DecodeLimits(v, maxNestingDepth, maxNumericLiteralDigits, maxBytesLiteralLength, maxRepeatedCount); }
    public DecodeLimits withMaxNestingDepth(int v)         { return new DecodeLimits(maxMessageSize, v, maxNumericLiteralDigits, maxBytesLiteralLength, maxRepeatedCount); }
    public DecodeLimits withMaxNumericLiteralDigits(int v) { return new DecodeLimits(maxMessageSize, maxNestingDepth, v, maxBytesLiteralLength, maxRepeatedCount); }
    public DecodeLimits withMaxBytesLiteralLength(int v)   { return new DecodeLimits(maxMessageSize, maxNestingDepth, maxNumericLiteralDigits, v, maxRepeatedCount); }
    public DecodeLimits withMaxRepeatedCount(int v)        { return new DecodeLimits(maxMessageSize, maxNestingDepth, maxNumericLiteralDigits, maxBytesLiteralLength, v); }

    /**
     * Sets one limit by its HARDENING.md name — the spelling
     * {@code check-decode --limit NAME=VALUE} takes.
     *
     * @throws IllegalArgumentException for a name that is not one of the
     *     five, or a value that is not positive
     */
    public DecodeLimits with(String name, int value) {
        return switch (name) {
            case "MaxMessageSize" -> withMaxMessageSize(value);
            case "MaxNestingDepth" -> withMaxNestingDepth(value);
            case "MaxNumericLiteralDigits" -> withMaxNumericLiteralDigits(value);
            case "MaxBytesLiteralLength" -> withMaxBytesLiteralLength(value);
            case "MaxRepeatedCount" -> withMaxRepeatedCount(value);
            default -> throw new IllegalArgumentException("unknown limit \"" + name + "\"");
        };
    }
}
