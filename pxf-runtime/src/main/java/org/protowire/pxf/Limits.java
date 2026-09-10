// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

/**
 * The decoder limits protowire's {@code docs/HARDENING.md} makes mandatory
 * for every port. Values are the cross-port defaults; a port that shipped
 * different ones would accept input the others reject, or the reverse.
 */
public final class Limits {
    private Limits() {}

    /**
     * {@code MaxNestingDepth}: the deepest PXF {@code { ... }} / {@code [ ... ]}
     * nesting, and the deepest protobuf submessage nesting, a decoder
     * follows. Depth 100 is accepted; depth 101 is rejected. Bounds native
     * call-stack growth and matches {@code google.golang.org/protobuf} and
     * {@code prost}. The PB codec ({@code org.protowire.pb.Pb}) carries the
     * same value, as it has no dependency on this module.
     */
    public static final int MAX_NESTING_DEPTH = 100;

    /**
     * {@code MaxNumericLiteralDigits}: the most decimal digits a PXF numeric
     * literal may carry before it reaches a big-number parser (a sign, a
     * point or an exponent marker does not count). {@code BigInteger} and
     * {@code BigDecimal} construction is superlinear in the digit count and
     * the literal's length is attacker-controlled; the check is one length
     * comparison on the way in. Fixed-width targets need no cap —
     * {@code Long.parseLong} bails on overflow in linear time. The same
     * constant bounds {@code pxf.Decimal.scale} on the PB wire, where a
     * scale is the digit count of the literal it stands for
     * ({@code org.protowire.pb.Pb} carries its own copy).
     */
    public static final int MAX_NUMERIC_LITERAL_DIGITS = 4096;

    /**
     * {@code MaxMessageSize}: the total input to one decode or parse call,
     * 64 MiB, checked before the first byte is read — peak memory is
     * otherwise a multiple of whatever the peer sends (#79). A stream's
     * frame cap may be lower and applies first.
     */
    public static final int MAX_MESSAGE_SIZE = 64 << 20;

    /**
     * {@code MaxBytesLiteralLength}: the decoded length of one {@code b"…"}
     * literal, checked from the literal's length before it is decoded.
     * Equal to {@code MaxMessageSize}.
     */
    public static final int MAX_BYTES_LITERAL_LENGTH = MAX_MESSAGE_SIZE;

    /**
     * {@code MaxRepeatedCount}: the element count of any repeated or map
     * field, checked as a counter on PXF and PB elements and on an SBE
     * group's wire-declared {@code numInGroup} before allocating for it.
     * Equal to {@code MaxMessageSize}.
     */
    public static final int MAX_REPEATED_COUNT = MAX_MESSAGE_SIZE;

    /**
     * Whether {@code n} bytes of input exceed {@code maxMessageSize}; the
     * shared message for the four decoders.
     */
    public static String messageSizeError(int n, int maxMessageSize) {
        return "input of " + n + " bytes exceeds MaxMessageSize=" + maxMessageSize;
    }
}
