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
}
