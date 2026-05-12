// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;

/**
 * Configurable PXF decoding options.
 *
 * @param typeResolver   resolves type URLs for {@code google.protobuf.Any}
 *                       sugar; nullable
 * @param discardUnknown silently skip fields not present in the schema
 *                       instead of erroring
 * @param skipValidate   bypass the per-call schema reserved-name check
 *                       (draft §3.13). Default-off (i.e. validation runs)
 *                       is the safe choice because reserved-name traps are
 *                       silent; pre-validating callers opt in to the skip
 */
public record UnmarshalOptions(TypeResolver typeResolver, boolean discardUnknown, boolean skipValidate) {

    public static UnmarshalOptions defaults() { return new UnmarshalOptions(null, false, false); }

    public UnmarshalOptions withTypeResolver(TypeResolver r) { return new UnmarshalOptions(r, discardUnknown, skipValidate); }
    public UnmarshalOptions withDiscardUnknown(boolean v)    { return new UnmarshalOptions(typeResolver, v, skipValidate); }
    public UnmarshalOptions withSkipValidate(boolean v)      { return new UnmarshalOptions(typeResolver, discardUnknown, v); }

    public DynamicMessage unmarshal(byte[] data, Descriptor desc) {
        DynamicMessage.Builder b = DynamicMessage.newBuilder(desc);
        unmarshal(data, b);
        return (DynamicMessage) b.build();
    }

    public void unmarshal(byte[] data, Message.Builder b) {
        new FastDecoder(data, this, /* trackPresence= */ false).decode(b);
    }

    public Result unmarshalFull(byte[] data, Message.Builder b) {
        FastDecoder d = new FastDecoder(data, this, /* trackPresence= */ true);
        d.decode(b);
        return d.result();
    }
}
