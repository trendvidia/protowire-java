// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Message;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Per-row proto-binding helper for {@code @table} rows. Sits atop the
 * streaming {@link TableReader} (via {@link TableReader#scan}) and is also
 * exported as a standalone helper for callers that iterate the
 * materializing path's {@link Result#tables()} rows.
 *
 * <p>Implementation strategy: convert each non-{@code null} cell back to
 * its PXF text representation, concatenate as a {@code <column> = <value>\n}
 * body, and run through the existing unmarshal pipeline with
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
     * <p>Cell-state semantics (mirrors draft §3.4.4):
     * <ul>
     *   <li>A {@code null} cell ⇒ field absent ({@code pxf.default} is
     *       applied if declared; {@code pxf.required} errors otherwise).</li>
     *   <li>An {@link Ast.NullVal} cell ⇒ field cleared, per §3.9
     *       (clears wrappers / optional / oneof).</li>
     *   <li>Any other {@link Ast.Value} ⇒ field set.</li>
     * </ul>
     *
     * <p>{@code builder}'s descriptor MUST contain fields whose names
     * appear in {@code columns}; a column referring to an unknown field
     * surfaces as a "field not found" error from the underlying unmarshal
     * call (unless {@link UnmarshalOptions#discardUnknown} is set).
     */
    public static void bindRow(Message.Builder builder, List<String> columns, Ast.TableRow row) {
        if (columns.size() != row.cells().size()) {
            throw new IllegalArgumentException(
                    "BindRow: " + columns.size() + " columns vs " + row.cells().size() + " cells");
        }
        byte[] body = rowToPxfBody(columns, row);
        // Run the synthetic body through the standard unmarshal pipeline.
        // SkipValidate avoids re-running the reserved-name check per row
        // (the caller's TableReader / unmarshalFull already validated the
        // descriptor once at bind time).
        UnmarshalOptions.defaults().withSkipValidate(true).unmarshal(body, builder);
    }

    /**
     * Render a row as a PXF body: one {@code <column> = <value>} entry per
     * non-{@code null} cell, in column order. Empty cells produce no
     * entry — the field stays absent from the decoder's perspective.
     */
    static byte[] rowToPxfBody(List<String> columns, Ast.TableRow row) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.cells().size(); i++) {
            Ast.Value cell = row.cells().get(i);
            if (cell == null) continue;
            sb.setLength(0);
            sb.append(columns.get(i)).append(" = ");
            writeCellValue(sb, cell);
            sb.append('\n');
            out.writeBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    /**
     * Format a single cell value as PXF text. v1 {@code @table} cells are
     * scalar-shaped (no list, no block), so only the leaf-value variants
     * appear; list and block AST nodes are unreachable here because
     * {@code parseTableRow} / {@code consumeRowCell} rejects them before
     * the streaming reader hands them to {@code bindRow}.
     */
    static void writeCellValue(StringBuilder sb, Ast.Value v) {
        switch (v) {
            case Ast.StringVal s -> {
                sb.append('"').append(Format.escape(s.value())).append('"');
            }
            case Ast.IntVal i -> sb.append(i.raw());
            case Ast.FloatVal f -> sb.append(f.raw());
            case Ast.BoolVal b -> sb.append(b.value() ? "true" : "false");
            case Ast.BytesVal by ->
                sb.append("b\"").append(Base64.getEncoder().encodeToString(by.value())).append('"');
            case Ast.NullVal n -> sb.append("null");
            case Ast.IdentVal id -> sb.append(id.name());
            case Ast.TimestampVal t -> sb.append(t.raw());
            case Ast.DurationVal d -> sb.append(d.raw());
            // v1 @table cells exclude list and block values; the parser
            // rejects these earlier. Hand-constructed TableRow values
            // bypass that check, so guard defensively.
            case Ast.ListVal l -> throw new IllegalArgumentException(
                    "BindRow: unexpected list value in cell (v1 @table cells are scalar-shaped)");
            case Ast.BlockVal bv -> throw new IllegalArgumentException(
                    "BindRow: unexpected block value in cell (v1 @table cells are scalar-shaped)");
        }
    }
}
