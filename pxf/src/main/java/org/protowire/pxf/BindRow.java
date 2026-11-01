// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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
 * <p>Implementation strategy: convert each non-{@code null} cell back to
 * its PXF text representation, concatenate as a {@code <column> = <value>\n}
 * body (dotted columns grouped into blocks), and run through the existing unmarshal pipeline with
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
        Body body = rowToPxfBody(columns, row);
        // SkipValidate avoids re-running the reserved-name check per row
        // (the caller's DatasetReader / unmarshalFull already validated the
        // descriptor once at bind time).
        UnmarshalOptions o = opts.withSkipValidate(true);
        try {
            // The full pipeline's post-decode pass gives an empty cell its
            // (pxf.default), rejects it for (pxf.required), and records a
            // null cell in a _null mask. It costs a presence record per row,
            // so only a row type that needs it pays for it.
            if (needsPresence(builder.getDescriptorForType())) o.unmarshalFull(body.text(), builder);
            else o.unmarshal(body.text(), builder);
        } catch (PxfException e) {
            throw body.atRow(e, row.pos(), columns);
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
        for (String c : columns) {
            Descriptor md = desc;
            int from = 0;
            while (true) {
                int dot = c.indexOf('.', from);
                String seg = dot < 0 ? c.substring(from) : c.substring(from, dot);
                FieldDescriptor fd = md.findFieldByName(seg);
                if (fd == null) {
                    throw new PxfException(pos, "@dataset column \"" + c + "\": " + md.getFullName() + " has no field \"" + seg + "\"");
                }
                if (dot < 0) break;
                String at = c.substring(0, dot);
                if (fd.isMapField()) {
                    throw new PxfException(pos, "@dataset column \"" + c + "\": \"" + at
                            + "\" is a map field, and a column path cannot go through one; use a value cell for \"" + at + "\" (draft §3.4.4)");
                }
                if (fd.isRepeated()) {
                    throw new PxfException(pos, "@dataset column \"" + c + "\": \"" + at
                            + "\" is a repeated field, and a column path cannot go through one; use a value cell for \"" + at + "\" (draft §3.4.4)");
                }
                if (fd.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
                    throw new PxfException(pos, "@dataset column \"" + c + "\": \"" + at + "\" is not a message field");
                }
                if (WellKnown.isDefaultableMessage(fd.getMessageType())) {
                    throw new PxfException(pos, "@dataset column \"" + c + "\": \"" + at + "\" is a "
                            + fd.getMessageType().getFullName() + ", which is written as a literal; a column path cannot go into it (draft §3.4.4)");
                }
                md = fd.getMessageType();
                from = dot + 1;
            }
        }
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

    // -- the synthetic body ------------------------------------------------

    /** One entry of a row body: a column's cell, or the block of a path prefix. */
    private static final class Node {
        final String name;
        final int col; // -1 for a block
        final List<Node> children = new ArrayList<>();
        Node(String name, int col) { this.name = name; this.col = col; }

        Node block(String seg) {
            for (Node ch : children) {
                if (ch.col < 0 && ch.name.equals(seg)) return ch;
            }
            Node next = new Node(seg, -1);
            children.add(next);
            return next;
        }

        int firstCol() { return col >= 0 ? col : children.get(0).firstCol(); }
    }

    /** The lines of a synthetic body that hold the entry of column {@code col}. */
    private record Span(int col, int first, int last) {}

    /** A row's synthetic body, and which lines came from which column. */
    record Body(byte[] text, List<Span> spans) {
        /**
         * Moves a decode error to the row it came from, naming the column it
         * fell in. The body opens with an empty line, so an error the decoder
         * reports with no position (a missing required field) names no column.
         */
        PxfException atRow(PxfException e, Position rowPos, List<String> columns) {
            String prefix = e.position() + ": ";
            String msg = e.getMessage().startsWith(prefix) ? e.getMessage().substring(prefix.length()) : e.getMessage();
            for (Span sp : spans) {
                if (e.position().line() >= sp.first() && e.position().line() <= sp.last()) {
                    msg = "column " + columns.get(sp.col()) + ": " + msg;
                    break;
                }
            }
            return new PxfException(rowPos, msg, e);
        }
    }

    /**
     * Render a row as a PXF body. A non-{@code null} cell in column {@code c}
     * is the entry {@code c = <value>}; one in a dotted column {@code a.b.c}
     * is the entry {@code c = <value>} inside {@code a { b { ... } }}, and
     * every cell whose column starts with {@code a.} lands in that one block:
     * two blocks for one sub-message would bind it twice. Empty cells produce
     * no entry, and a block none of whose cells is non-empty is not written,
     * so the field stays absent.
     */
    static Body rowToPxfBody(List<String> columns, Ast.DatasetRow row) {
        Node root = new Node("", -1);
        for (int i = 0; i < row.cells().size(); i++) {
            if (row.cells().get(i) == null) continue;
            String[] segs = columns.get(i).split("\\.", -1);
            Node n = root;
            for (int s = 0; s < segs.length - 1; s++) n = n.block(segs[s]);
            n.children.add(new Node(segs[segs.length - 1], i));
        }
        StringBuilder sb = new StringBuilder("\n");
        List<Span> spans = new ArrayList<>(row.cells().size());
        writeBlock(root, row, sb, new int[] {2}, spans);
        return new Body(sb.toString().getBytes(StandardCharsets.UTF_8), spans);
    }

    private static void writeBlock(Node n, Ast.DatasetRow row, StringBuilder sb, int[] line, List<Span> spans) {
        for (Node ch : n.children) {
            if (ch.col >= 0) {
                int start = sb.length();
                sb.append(ch.name).append(" = ");
                writeCellValue(sb, row.cells().get(ch.col));
                sb.append('\n');
                int last = line[0] + (int) sb.substring(start).chars().filter(c -> c == '\n').count() - 1;
                spans.add(new Span(ch.col, line[0], last));
                line[0] = last + 1;
                continue;
            }
            // An error on the block's own lines (a second oneof member, say)
            // is laid at the first column in it. Its span comes after its
            // cells', so a cell's wins.
            int first = line[0]++;
            sb.append(ch.name).append(" {\n");
            writeBlock(ch, row, sb, line, spans);
            sb.append("}\n");
            spans.add(new Span(ch.firstCol(), first, line[0]));
            line[0]++;
        }
    }

    /**
     * Format a single cell value as PXF text. A list or block cell is written
     * with the package formatter, so the body binds it exactly as
     * {@code <column> = <value>} would bind in a document.
     *
     * <p>The {@code NullVal} case doesn't read a bound variable, so it's
     * checked via {@code instanceof} before the value-using switch: Java 21
     * standard pattern matching requires a binding on every {@code case}
     * label, and CodeQL flags an unused one.
     */
    static void writeCellValue(StringBuilder sb, Ast.Value v) {
        if (v instanceof Ast.NullVal) {
            sb.append("null");
            return;
        }
        switch (v) {
            case Ast.StringVal s ->
                sb.append('"').append(Format.escape(s.value())).append('"');
            case Ast.IntVal i -> sb.append(i.literal());
            case Ast.FloatVal f -> sb.append(f.literal());
            case Ast.BoolVal b -> sb.append(b.value() ? "true" : "false");
            case Ast.BytesVal by ->
                sb.append("b\"").append(Base64.getEncoder().encodeToString(by.value())).append('"');
            case Ast.IdentVal id -> sb.append(id.name());
            case Ast.TimestampVal t -> sb.append(t.raw());
            case Ast.DurationVal d -> sb.append(d.raw());
            case Ast.ListVal l -> Format.formatValue(sb, l, 0);
            case Ast.BlockVal bv -> Format.formatValue(sb, bv, 0);
            default -> throw new IllegalArgumentException(
                    "BindRow: unexpected cell value type " + v.getClass().getSimpleName());
        }
    }
}
