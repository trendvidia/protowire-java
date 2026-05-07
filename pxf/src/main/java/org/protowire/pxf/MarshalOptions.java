// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Message;

/** Configurable PXF encoding options. */
public record MarshalOptions(
        String indent,
        boolean emitDefaults,
        String typeUrl,
        TypeResolver typeResolver,
        Result nullFields) {

    public static MarshalOptions defaults() {
        return new MarshalOptions("  ", false, "", null, null);
    }

    public MarshalOptions withIndent(String s)         { return new MarshalOptions(s, emitDefaults, typeUrl, typeResolver, nullFields); }
    public MarshalOptions withEmitDefaults(boolean v)  { return new MarshalOptions(indent, v, typeUrl, typeResolver, nullFields); }
    public MarshalOptions withTypeUrl(String s)        { return new MarshalOptions(indent, emitDefaults, s, typeResolver, nullFields); }
    public MarshalOptions withTypeResolver(TypeResolver r) { return new MarshalOptions(indent, emitDefaults, typeUrl, r, nullFields); }
    public MarshalOptions withNullFields(Result r)     { return new MarshalOptions(indent, emitDefaults, typeUrl, typeResolver, r); }

    public byte[] marshal(Message m) { return new Encoder(this).encode(m); }
}
