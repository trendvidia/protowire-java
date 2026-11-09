// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
     * Appends the header {@code @dataset <type> (<columns>)} for rows of type
     * {@code desc}, and a newline, to {@code dst}, as protowire-go's
     * {@code AppendDatasetHeader} (#204).
     *
     * <p>Any field of {@code desc} can be a column, a cell holding a list or
     * a block too (protowire#341), and so can a path into a sub-message such
     * as {@code inner.x} (protowire#342), through singular message fields
     * only.
     *
     * @throws PxfException with {@code dst} unchanged, for an empty column
     *     list, a column the reader would refuse (a name that is not a field,
     *     an empty segment, a column twice, a field beside a path inside it,
     *     a path through a repeated or map field or into a type written as a
     *     literal), a {@code (pxf.required)} field of {@code desc} not among
     *     the columns, or a schema {@link SchemaValidator} refuses
     */
    public static StringBuilder appendDatasetHeader(StringBuilder dst, Descriptor desc, List<String> columns) {
        return DatasetWriter.appendHeader(dst, desc, columns);
    }

    /**
     * Appends {@code msg} as one row of a {@code @dataset} table with these
     * columns, and a newline, to {@code dst}, as protowire-go's
     * {@code AppendDatasetRow} (#204). Use the columns given to
     * {@link #appendDatasetHeader}. {@link DatasetReader} reads the row back
     * to {@code msg}, but for fields outside the columns.
     *
     * <p>A set field is written as {@link #marshal} writes its value. An
     * unset field is an empty cell, which reads back as absent, except where
     * reading an empty cell would not give back the unset field:
     * <ul>
     *   <li>A field with {@code (pxf.default)} would take the default. A
     *       scalar without presence is written as its zero value, and a
     *       message-typed field (a wrapper, a Timestamp) {@code null}. A
     *       scalar with presence ({@code optional}, or a oneof member) stays
     *       empty and reads back as its default. A oneof member stays empty
     *       when another member of its oneof is set and among the columns.
     *   <li>A {@code (pxf.required)} field would be rejected. A scalar
     *       without presence is written as its zero value, an empty repeated
     *       field {@code []} and an empty map {@code {}}, which the reader
     *       counts as supplied; an unset one with presence is an error.
     * </ul>
     * A message-typed field named in the message's {@code _null} mask is
     * written {@code null}. A list, map or block is written as marshal writes
     * it, on one line. A dotted column is written through its parents: an
     * unset parent makes the cell empty. A row the reader would reject is
     * refused: a required sub-message whose cells are all empty, or one that
     * reads back present while one of its required fields is not a column.
     *
     * @throws PxfException with {@code dst} unchanged, for the column errors
     *     of {@link #appendDatasetHeader}, and for a value marshal refuses
     */
    public static StringBuilder appendDatasetRow(StringBuilder dst, Message msg, List<String> columns) {
        return DatasetWriter.appendRow(dst, msg, columns);
    }

    /**
     * Whether {@code fd} is required, by {@code [(pxf.required) = true]} or by
     * {@code @required}. For a caller that runs its own pass with
     * {@link UnmarshalOptions#skipPostDecode()}.
     */
    public static boolean isRequired(FieldDescriptor fd) {
        return Annotations.isRequired(fd);
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
     * {@link Result#presentFieldsView()}). Setting one member clears the
     * others, so the default would destroy the arm that was chosen.
     *
     * @throws PxfException if the field cannot hold a default or the literal
     *                      does not fit it
     */
    public static void applyDefault(Message.Builder builder, FieldDescriptor fd, String literal) {
        FastDecoder.applyDefault(builder, fd, literal);
    }
}
