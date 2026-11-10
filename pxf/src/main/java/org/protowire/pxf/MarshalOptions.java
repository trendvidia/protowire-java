// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Message;

/**
 * Configurable PXF encoding options.
 *
 * @param maxNestingDepth the deepest document {@link #marshal} writes,
 *     counted as a decoder counts it: the root is depth 0, and every
 *     '{' or '[' is one descent. A message that needs more is an
 *     error, because a reader at the same limit would refuse the document
 *     (HARDENING.md § Recursion, protowire#502). An Any past the limit is
 *     not: it stays as {@code type_url} and {@code value}. Raise it only with
 *     the same limit on every reader.
 */
public record MarshalOptions(
        String indent,
        boolean emitDefaults,
        String typeUrl,
        TypeResolver typeResolver,
        Result nullFields,
        int maxNestingDepth) {

    public MarshalOptions {
        if (maxNestingDepth <= 0) {
            throw new IllegalArgumentException("MaxNestingDepth must be positive, got " + maxNestingDepth);
        }
    }

    /** As the six-argument form at the default {@code MaxNestingDepth}. */
    public MarshalOptions(String indent, boolean emitDefaults, String typeUrl, TypeResolver typeResolver, Result nullFields) {
        this(indent, emitDefaults, typeUrl, typeResolver, nullFields, Limits.MAX_NESTING_DEPTH);
    }

    public static MarshalOptions defaults() {
        return new MarshalOptions("  ", false, "", null, null);
    }

    public MarshalOptions withIndent(String s)         { return new MarshalOptions(s, emitDefaults, typeUrl, typeResolver, nullFields, maxNestingDepth); }
    public MarshalOptions withEmitDefaults(boolean v)  { return new MarshalOptions(indent, v, typeUrl, typeResolver, nullFields, maxNestingDepth); }
    public MarshalOptions withTypeUrl(String s)        { return new MarshalOptions(indent, emitDefaults, s, typeResolver, nullFields, maxNestingDepth); }
    public MarshalOptions withTypeResolver(TypeResolver r) { return new MarshalOptions(indent, emitDefaults, typeUrl, r, nullFields, maxNestingDepth); }
    public MarshalOptions withNullFields(Result r)     { return new MarshalOptions(indent, emitDefaults, typeUrl, typeResolver, r, maxNestingDepth); }
    public MarshalOptions withMaxNestingDepth(int v)   { return new MarshalOptions(indent, emitDefaults, typeUrl, typeResolver, nullFields, v); }

    public byte[] marshal(Message m) { return new Encoder(this).encode(m); }
}
