// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-row proto-binding helper for {@code @dataset} rows. Sits atop the
 * streaming {@link DatasetReader} (via {@link DatasetReader#scan}) and is also
 * exported as a standalone helper for callers that iterate the
 * materializing path's {@link Result#datasets()} rows.
 *
 * <p>Implementation strategy: write the row as the body that binds it
 * ({@link RowBody}, which the lite tier's row binder shares), and run it
 * through the existing unmarshal pipeline with
 * {@link UnmarshalOptions#skipValidate()} on. That reuses every branch of
 * the existing decoder — WKT timestamps and durations, wrapper-type
 * nullability, enum-by-name resolution, {@code pxf.required} /
 * {@code pxf.default}, oneof handling — instead of growing a parallel
 * Value-to-FieldDescriptor switch with ~50 arms. The cost is a small
 * format-and-reparse per row; that's an acceptable trade for a streaming
 * convenience API whose consumers have already opted into the convenience
 * tier. Same trade {@code protowire-go} made in {@code table_bind.go}.
 */
public final class BindRow {
    private BindRow() {}

    /**
     * Bind the cells of {@code row} to the fields of {@code builder} by
     * column name. The {@code columns} list MUST have the same length as
     * {@code row.cells()}; mismatch raises {@link IllegalArgumentException}.
     *
     * <p>A column names a field of {@code builder}'s type or, as a dotted
     * path such as {@code desc.energy}, a field inside one of its
     * sub-messages. Every cell of a row that shares a path prefix lands in
     * one block.
     *
     * <p>Cell-state semantics (mirrors draft §3.4.4):
     * <ul>
     *   <li>A {@code null} cell ⇒ field absent ({@code pxf.default} is
     *       applied if declared; {@code pxf.required} errors otherwise).</li>
     *   <li>An {@link Ast.NullVal} cell ⇒ present-but-null, under the same
     *       rule as a body's {@code null}: rejected on a field without
     *       explicit presence.</li>
     *   <li>Any other {@link Ast.Value} ⇒ the field is bound as the body
     *       entry {@code column = value} would bind it, so a list or block
     *       cell is a list or message, and {@code {}} is a present empty
     *       message.</li>
     * </ul>
     *
     * <p>{@code builder}'s descriptor MUST contain the fields {@code columns}
     * names, and no path may go through a repeated, map or scalar field or
     * into a type written as a literal; a broken column fails every row
     * bound into that type, its cells empty or not. A decode error is
     * reported at the row, naming the column it fell in.
     */
    public static void bindRow(Message.Builder builder, List<String> columns, Ast.DatasetRow row) {
        bindRow(UnmarshalOptions.defaults(), builder, columns, row);
    }

    /** As {@link #bindRow(Message.Builder, List, Ast.DatasetRow)} under {@code opts}: its limits and type resolver. */
    public static void bindRow(UnmarshalOptions opts, Message.Builder builder, List<String> columns, Ast.DatasetRow row) {
        if (columns.size() != row.cells().size()) {
            throw new IllegalArgumentException(
                    "BindRow: " + columns.size() + " columns vs " + row.cells().size() + " cells");
        }
        checkColumnPaths(builder.getDescriptorForType(), columns, row.pos());
        bind(opts, builder, columns, row);
    }

    /** {@link #bindRow} for a caller that has checked the column paths. */
    static void bind(UnmarshalOptions opts, Message.Builder builder, List<String> columns, Ast.DatasetRow row) {
        // Every option the caller set applies to the row, as protowire-go's
        // BindRow and DatasetReader.Scan bind it (protowire-go#280, #194),
        // except MaxMessageSize: the row's body repeats the column names, so
        // it is wider than the row, and is bound at the default. The schema
        // is checked here, outside the try, so a violation comes back as it
        // is: only a decode error is the row's, and carries its position.
        // The check is cached per descriptor.
        if (!opts.skipValidate()) {
            String violations = SchemaValidator.asMessage(SchemaValidator.validateDescriptor(builder.getDescriptorForType()));
            if (violations != null) throw new PxfException(Position.UNKNOWN, violations);
        }
        RowBody body = RowBody.of(columns, row);
        UnmarshalOptions o = opts.withSkipValidate(true)
                .withLimits(opts.limits().withMaxMessageSize(DecodeLimits.defaults().maxMessageSize()));
        try {
            // The full pipeline's post-decode pass gives an empty cell its
            // (pxf.default), rejects it for (pxf.required), and records a
            // null cell in a _null mask. It costs a presence record per row,
            // so only a row type that needs it pays for it.
            if (needsPresence(builder.getDescriptorForType())) o.unmarshalFull(body.text(), builder);
            else o.unmarshal(body.text(), builder);
        } catch (PxfException e) {
            throw body.atRow(e);
        }
    }

    // -- column paths ------------------------------------------------------

    /**
     * Checks {@code columns} against the row type (draft §3.4.4,
     * protowire#342): each segment names a field, and each segment but the
     * last names a singular message field whose type is not written as a
     * literal. A path has no index, so it cannot go through a repeated or map
     * field. A {@code pxf.Secret} has a block form as well as its literal, so
     * a path may go into one. Mirrors protowire-go's {@code checkColumnPaths}.
     */
    static void checkColumnPaths(Descriptor desc, List<String> columns, Position pos) {
        String problem = columnSchemaProblem(desc, columns);
        if (problem != null) throw new PxfException(pos, problem);
    }

    /** {@link #checkColumnPaths}'s rules: the first column the row type refuses, as a message, or null. */
    static String columnSchemaProblem(Descriptor desc, List<String> columns) {
        for (String c : columns) {
            Descriptor md = desc;
            int from = 0;
            while (true) {
                int dot = c.indexOf('.', from);
                String seg = dot < 0 ? c.substring(from) : c.substring(from, dot);
                FieldDescriptor fd = md.findFieldByName(seg);
                if (fd == null) {
                    return "@dataset column \"" + c + "\": " + md.getFullName() + " has no field \"" + seg + "\"";
                }
                if (dot < 0) break;
                String at = c.substring(0, dot);
                if (fd.isMapField()) {
                    return "@dataset column \"" + c + "\": \"" + at
                            + "\" is a map field, and a column path cannot go through one; use a value cell for \"" + at + "\" (draft §3.4.4)";
                }
                if (fd.isRepeated()) {
                    return "@dataset column \"" + c + "\": \"" + at
                            + "\" is a repeated field, and a column path cannot go through one; use a value cell for \"" + at + "\" (draft §3.4.4)";
                }
                if (fd.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
                    return "@dataset column \"" + c + "\": \"" + at + "\" is not a message field";
                }
                if (WellKnown.isDefaultableMessage(fd.getMessageType())) {
                    return "@dataset column \"" + c + "\": \"" + at + "\" is a "
                            + fd.getMessageType().getFullName() + ", which is written as a literal; a column path cannot go into it (draft §3.4.4)";
                }
                md = fd.getMessageType();
                from = dot + 1;
            }
        }
        return null;
    }

    // -- presence ----------------------------------------------------------

    /** Row types {@link #needsPresence} has answered for; bounded because descriptors can be built at run time. */
    private static final Map<Descriptor, Boolean> PRESENCE = new ConcurrentHashMap<>();
    private static final int MAX_CACHED = 4096;

    /**
     * Whether a row of type {@code desc} must go through the full pipeline: a
     * field carries {@code (pxf.default)} or {@code (pxf.required)}, in the
     * row type or a sub-message a path or a block cell can fill, or
     * {@code desc} declares a {@code _null} mask.
     */
    private static boolean needsPresence(Descriptor desc) {
        Boolean hit = PRESENCE.get(desc);
        if (hit != null) return hit;
        boolean need = WellKnown.findNullMaskField(desc) != null || hasAnnotation(desc, new HashSet<>());
        if (PRESENCE.size() < MAX_CACHED) PRESENCE.put(desc, need);
        return need;
    }

    private static boolean hasAnnotation(Descriptor desc, Set<String> seen) {
        if (!seen.add(desc.getFullName())) return false;
        if (WellKnown.isAny(desc)) return true; // the type it names is known only from the row
        for (FieldDescriptor fd : desc.getFields()) {
            if (Annotations.isRequired(fd) || Annotations.getDefault(fd) != null) return true;
            if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE && hasAnnotation(fd.getMessageType(), seen)) return true;
        }
        return false;
    }
}
