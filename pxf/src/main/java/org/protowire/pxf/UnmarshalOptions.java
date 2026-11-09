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
 * @param onSecretField  called with each {@code pxf.Secret} value in place of
 *                       storing it; nullable. See {@link OnSecretField}
 */
public record UnmarshalOptions(TypeResolver typeResolver, boolean discardUnknown, boolean skipValidate, DecodeLimits limits,
                               boolean skipPostDecode, OnSecretField onSecretField) {

    /**
     * Receives each {@code pxf.Secret} value the decoder reads, in place of
     * storing it in the message, as protowire-go's {@code OnSecretField}
     * (#206): so a caller can route secrets to protected memory and keep them
     * out of the decoded message.
     *
     * <p>It is called in every form a Secret can be written: the literal
     * ({@code pw = "x"}), a list element ({@code ["a", "b"]}), a map value
     * ({@code { "acme": "k" }}) and the block form
     * ({@code pw { value = "x"  hint = "h" }}). Each time the decoder leaves
     * the Secret's {@code value} unset, and still records its presence, so
     * {@link Result#isSet} reports it; {@code hint} and {@code fingerprint}
     * are stored as usual. The path is the field's dotted path, as the
     * presence paths spell it:
     * <pre>
     *   pw = "x"                               → pw
     *   db { password = "x" }                  → db.password
     *   pw { value = "x"  hint = "h" }         → pw
     *   servers = [{ password = "x" }]         → servers[0].password
     *   by_host = { "a": { password = "x" } }  → by_host["a"].password
     *   backup_keys = ["a", "b"]               → backup_keys[0], backup_keys[1]
     *   tenant_keys = { "acme": "k" }          → tenant_keys["acme"]
     * </pre>
     * A dataset row's cell is a field of the row, so its path is the column.
     * The plaintext also exists in the input for the length of the decode.
     */
    @FunctionalInterface
    public interface OnSecretField {
        /**
         * @throws Exception to abort the decode, which then fails with a
         *     {@link PxfException} at the value: {@code pxf.Secret hook for
         *     "<path>": <its message>}
         */
        void accept(String path, String value) throws Exception;
    }

    /** As the six-argument form, with no Secret hook. */
    public UnmarshalOptions(TypeResolver typeResolver, boolean discardUnknown, boolean skipValidate, DecodeLimits limits,
                            boolean skipPostDecode) {
        this(typeResolver, discardUnknown, skipValidate, limits, skipPostDecode, null);
    }

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

    public UnmarshalOptions withTypeResolver(TypeResolver r) { return new UnmarshalOptions(r, discardUnknown, skipValidate, limits, skipPostDecode, onSecretField); }
    public UnmarshalOptions withDiscardUnknown(boolean v)    { return new UnmarshalOptions(typeResolver, v, skipValidate, limits, skipPostDecode, onSecretField); }
    public UnmarshalOptions withSkipValidate(boolean v)      { return new UnmarshalOptions(typeResolver, discardUnknown, v, limits, skipPostDecode, onSecretField); }
    public UnmarshalOptions withLimits(DecodeLimits l)       { return new UnmarshalOptions(typeResolver, discardUnknown, skipValidate, l, skipPostDecode, onSecretField); }
    public UnmarshalOptions withSkipPostDecode(boolean v)    { return new UnmarshalOptions(typeResolver, discardUnknown, skipValidate, limits, v, onSecretField); }
    public UnmarshalOptions withOnSecretField(OnSecretField h) { return new UnmarshalOptions(typeResolver, discardUnknown, skipValidate, limits, skipPostDecode, h); }

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
