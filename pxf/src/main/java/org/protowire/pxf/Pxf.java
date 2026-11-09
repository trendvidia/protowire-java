// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;

import java.nio.charset.StandardCharsets;

/**
 * Top-level entry points for PXF encoding/decoding.
 *
 * <p>Mirrors the Go module's {@code pxf.Marshal}, {@code pxf.Unmarshal}, {@code pxf.Parse},
 * {@code pxf.UnmarshalFull}.
 *
 * <p>As in the Go module, only {@code unmarshalFull} applies {@code (pxf.default)}
 * and checks {@code (pxf.required)}. {@code unmarshal} does neither, so it accepts
 * a document that omits a required field.
 */
public final class Pxf {
    private Pxf() {}

    // -- AST path ----------------------------------------------------------

    public static Ast.Document parse(byte[] data) { return Parser.parse(data); }
    public static Ast.Document parse(String data) { return Parser.parse(data); }

    public static byte[] formatDocument(Ast.Document doc) {
        return Format.formatDocument(doc).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The schema-aware {@code fmt}: {@link KeyedCanonicalizer#canonicalize}
     * against {@code desc}, then {@link #formatDocument}. Keyed repeated
     * fields (draft -01 §3.13) come out in their canonical block form; a
     * document that does not resolve against the schema is formatted as it
     * is.
     */
    public static byte[] formatDocument(Ast.Document doc, Descriptor desc) {
        return formatDocument(KeyedCanonicalizer.canonicalize(doc, desc));
    }

    // -- decode (fused fast path) -----------------------------------------

    public static byte[] marshal(Message msg) { return new Encoder(MarshalOptions.defaults()).encode(msg); }

    /** Decodes without applying {@code (pxf.default)} or checking {@code (pxf.required)}. */
    public static DynamicMessage unmarshal(byte[] data, Descriptor desc) {
        return UnmarshalOptions.defaults().unmarshal(data, desc);
    }

    /** Decodes without applying {@code (pxf.default)} or checking {@code (pxf.required)}. */
    public static void unmarshal(byte[] data, Message.Builder builder) {
        UnmarshalOptions.defaults().unmarshal(data, builder);
    }

    /** Decodes, records presence, applies {@code (pxf.default)} and checks {@code (pxf.required)}. */
    public static Result unmarshalFull(byte[] data, Message.Builder builder) {
        return UnmarshalOptions.defaults().unmarshalFull(data, builder);
    }

    public static Result unmarshalFull(byte[] data, Descriptor desc, Message.Builder builder) {
        return UnmarshalOptions.defaults().unmarshalFull(data, builder);
    }

    // -- post-decode rules, for a caller using skipPostDecode --------------

    /**
     * Whether {@code fd} is required, by {@code [(pxf.required) = true]} or by
     * {@code @required}. For a caller that runs its own pass with
     * {@link UnmarshalOptions#skipPostDecode()}.
     */
    public static boolean isRequired(FieldDescriptor fd) {
        return Annotations.isRequired(fd);
    }

    /**
     * The field name {@code fd}'s {@code (pxf.key)} annotation designates as
     * the key of a keyed repeated field (draft -01 §3.13), or null when it has
     * none. Returned even when its placement is invalid, as protowire-go's
     * {@code KeyFieldName} is, for tooling that reports on what was written;
     * {@link #keyField} resolves it only when it is well placed, and
     * {@link SchemaValidator} reports a bad placement.
     */
    public static String keyFieldName(FieldDescriptor fd) {
        return Annotations.getKey(fd);
    }

    /**
     * The key field of a keyed repeated field: the singular string field of
     * {@code fd}'s element message that its {@code (pxf.key)} names, or null
     * when {@code fd} has no {@code (pxf.key)} or it is placed where it cannot
     * apply. As protowire-go's {@code KeyField}.
     */
    public static FieldDescriptor keyField(FieldDescriptor fd) {
        return Annotations.keyField(fd);
    }

    /** Whether {@code fd} is a keyed repeated field: {@link #keyField} is not null. As protowire-go's {@code IsKeyed}. */
    public static boolean isKeyed(FieldDescriptor fd) {
        return Annotations.keyField(fd) != null;
    }

    /**
     * {@code fd}'s default as a PXF literal, from {@code (pxf.default)} or
     * {@code @default}, or null if it has none. Pass it to
     * {@link #applyDefault}.
     */
    public static String defaultLiteral(FieldDescriptor fd) {
        return Annotations.getDefault(fd);
    }

    /**
     * Sets {@code fd} on {@code builder} from a default literal.
     *
     * <p>Do not call this for a member of a real oneof when any member of that
     * oneof is present (a member bound to null counts, see
     * {@link Result#presentFields()}). Setting one member clears the
     * others, so the default would destroy the arm that was chosen.
     *
     * @throws PxfException if the field cannot hold a default or the literal
     *                      does not fit it
     */
    public static void applyDefault(Message.Builder builder, FieldDescriptor fd, String literal) {
        FastDecoder.applyDefault(builder, fd, literal);
    }
}
