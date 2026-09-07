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
}
