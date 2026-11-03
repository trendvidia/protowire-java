// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pb;

/**
 * Per-call options for {@link Pb#marshal(Object, MarshalOptions)}, as
 * protowire-go's {@code pb.MarshalOptions}.
 *
 * @param maxNestingDepth the deepest value {@link Pb#marshal} writes, counted
 *     as a decoder counts it: the root is depth 0, and every submessage or map
 *     entry is one descent. A value that needs more is refused with an
 *     {@code IOException}, because a reader at the same limit would refuse the
 *     bytes, and a reference cycle meets the limit instead of the stack
 *     (HARDENING.md § Recursion, protowire#502, #167). Raise it only with
 *     {@link UnmarshalOptions#maxNestingDepth()} on every reader.
 */
public record MarshalOptions(int maxNestingDepth) {

    public MarshalOptions {
        if (maxNestingDepth <= 0) {
            throw new IllegalArgumentException("MaxNestingDepth must be positive, got " + maxNestingDepth);
        }
    }

    /** The cross-port default, {@link Pb#MAX_NESTING_DEPTH}. */
    public static MarshalOptions defaults() {
        return new MarshalOptions(Pb.MAX_NESTING_DEPTH);
    }

    public MarshalOptions withMaxNestingDepth(int v) { return new MarshalOptions(v); }
}
