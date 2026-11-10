// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A {@code @dataset} row written as the message body that binds it (draft
 * §3.4.4): the step both tiers' row binders share, the full tier's
 * {@code BindRow} and the lite tier's {@code LiteWireWriter.encodeRow}, so a
 * row binds, and fails, the same way in each (#212). protowire-go's
 * {@code BindRow} binds a row the same way.
 *
 * <p>A non-empty cell in column {@code c} is the entry {@code c = <value>}.
 * One in a dotted column {@code a.b.c} is the entry {@code c = <value>} inside
 * {@code a { b { ... } }}, and every cell whose column starts with {@code a.}
 * lands in that one block: two blocks for one sub-message would bind it
 * twice. An empty cell writes no entry, and a block none of whose cells is
 * non-empty is not written, so the field stays absent.
 */
public final class RowBody {

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

    /** The lines of a body that hold the entry of column {@code col}. */
    private record Span(int col, int first, int last) {}

    private final byte[] text;
    private final List<Span> spans;
    private final List<String> columns;
    private final Position rowPos;

    private RowBody(byte[] text, List<Span> spans, List<String> columns, Position rowPos) {
        this.text = text;
        this.spans = spans;
        this.columns = columns;
        this.rowPos = rowPos;
    }

    /**
     * {@code row} written as a body, its cells bound to {@code columns} in
     * order. The body opens with an empty line, so an error a decoder
     * reports with no position (a missing required field) names no column.
     *
     * @throws IllegalArgumentException if the row's cells and the columns differ in number
     */
    public static RowBody of(List<String> columns, Ast.DatasetRow row) {
        if (row.cells().size() != columns.size()) {
            throw new IllegalArgumentException(
                    "RowBody: " + columns.size() + " columns vs " + row.cells().size() + " cells");
        }
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
        return new RowBody(sb.toString().getBytes(StandardCharsets.UTF_8), spans, List.copyOf(columns), row.pos());
    }

    /** The body's PXF text. */
    public byte[] text() {
        return text.clone();
    }

    /**
     * {@code e}, an error decoding {@link #text()}, moved to the row it came
     * from and naming the column it fell in, as {@code column c: <message>}.
     */
    public PxfException atRow(PxfException e) {
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
     * A cell value as PXF text. A list or block cell is written with the
     * package formatter, so the body binds it exactly as
     * {@code <column> = <value>} would bind in a document.
     *
     * <p>The {@code NullVal} case reads no bound variable, so it is checked
     * with {@code instanceof} before the switch: Java 21 pattern matching
     * needs a binding on every {@code case} label, and CodeQL flags an
     * unused one.
     */
    private static void writeCellValue(StringBuilder sb, Ast.Value v) {
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
                    "RowBody: unexpected cell value type " + v.getClass().getSimpleName());
        }
    }
}
