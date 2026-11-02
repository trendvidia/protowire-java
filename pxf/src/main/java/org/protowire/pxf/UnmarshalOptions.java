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
 * @param limits         the draft's per-call limits (§ Mandatory Limits,
 *                       #79); {@link DecodeLimits#defaults()} is the
 *                       cross-port constants
 * @param skipPostDecode on {@link #unmarshalFull}, do not apply
 *                       {@code (pxf.default)} or check {@code (pxf.required)}.
 *                       Presence is still recorded in the {@link Result}, for
 *                       a caller that applies the rules itself, typically
 *                       after merging layered documents: a base layer may omit
 *                       a required field that a higher layer provides. Has no
 *                       effect on {@link #unmarshal}, which never applies them.
 *                       Mirrors protowire-go's {@code SkipPostDecode}
 */
public record UnmarshalOptions(TypeResolver typeResolver, boolean discardUnknown, boolean skipValidate, DecodeLimits limits, boolean skipPostDecode) {

    /** As the five-argument form, with the post-decode pass on. */
    public UnmarshalOptions(TypeResolver typeResolver, boolean discardUnknown, boolean skipValidate, DecodeLimits limits) {
        this(typeResolver, discardUnknown, skipValidate, limits, false);
    }

    /** As the four-argument form with {@link DecodeLimits#defaults()}. */
    public UnmarshalOptions(TypeResolver typeResolver, boolean discardUnknown, boolean skipValidate) {
        this(typeResolver, discardUnknown, skipValidate, DecodeLimits.defaults());
    }

    public UnmarshalOptions {
        if (limits == null) limits = DecodeLimits.defaults();
    }

    public static UnmarshalOptions defaults() { return new UnmarshalOptions(null, false, false); }

    public UnmarshalOptions withTypeResolver(TypeResolver r) { return new UnmarshalOptions(r, discardUnknown, skipValidate, limits, skipPostDecode); }
    public UnmarshalOptions withDiscardUnknown(boolean v)    { return new UnmarshalOptions(typeResolver, v, skipValidate, limits, skipPostDecode); }
    public UnmarshalOptions withSkipValidate(boolean v)      { return new UnmarshalOptions(typeResolver, discardUnknown, v, limits, skipPostDecode); }
    public UnmarshalOptions withLimits(DecodeLimits l)       { return new UnmarshalOptions(typeResolver, discardUnknown, skipValidate, l, skipPostDecode); }
    public UnmarshalOptions withSkipPostDecode(boolean v)    { return new UnmarshalOptions(typeResolver, discardUnknown, skipValidate, limits, v); }

    /** See {@link #unmarshal(byte[], Message.Builder)}. */
    public DynamicMessage unmarshal(byte[] data, Descriptor desc) {
        DynamicMessage.Builder b = DynamicMessage.newBuilder(desc);
        unmarshal(data, b);
        return (DynamicMessage) b.build();
    }

    /**
     * Decodes without tracking presence, so {@code (pxf.default)} is not
     * applied and {@code (pxf.required)} is not checked. A document that omits
     * a required field decodes without error here. Use {@link #unmarshalFull}
     * for those rules.
     */
    public void unmarshal(byte[] data, Message.Builder b) {
        new FastDecoder(data, this, /* trackPresence= */ false).decode(b);
    }

    /**
     * Decodes, records field presence, applies {@code (pxf.default)} and checks
     * {@code (pxf.required)} in every message body, unless
     * {@link #skipPostDecode()} is set.
     */
    public Result unmarshalFull(byte[] data, Message.Builder b) {
        FastDecoder d = new FastDecoder(data, this, /* trackPresence= */ true);
        d.decode(b);
        return d.result();
    }
}
