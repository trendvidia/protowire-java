// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Message;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Per-row proto-binding helper for {@code @dataset} rows. Sits atop the
 * streaming {@link DatasetReader} (via {@link DatasetReader#scan}) and is also
 * exported as a standalone helper for callers that iterate the
 * materializing path's {@link Result#datasets()} rows.
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
    public static void bindRow(Message.Builder builder, List<String> columns, Ast.DatasetRow row) {
        if (columns.size() != row.cells().size()) {
            throw new IllegalArgumentException(
                    "BindRow: " + columns.size() + " columns vs " + row.cells().size() + " cells");
        }
        byte[] body = rowToPxfBody(columns, row);
        // Run the synthetic body through the standard unmarshal pipeline.
        // SkipValidate avoids re-running the reserved-name check per row
        // (the caller's DatasetReader / unmarshalFull already validated the
        // descriptor once at bind time).
        UnmarshalOptions.defaults().withSkipValidate(true).unmarshal(body, builder);
    }

    /**
     * Render a row as a PXF body: one {@code <column> = <value>} entry per
     * non-{@code null} cell, in column order. Empty cells produce no
     * entry — the field stays absent from the decoder's perspective.
     */
    static byte[] rowToPxfBody(List<String> columns, Ast.DatasetRow row) {
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
     * Format a single cell value as PXF text. v1 {@code @dataset} cells are
     * scalar-shaped (no list, no block), so only the leaf-value variants
     * appear; list and block AST nodes are unreachable here because
     * {@code parseDatasetRow} / {@code consumeRowCell} rejects them before
     * the streaming reader hands them to {@code bindRow}. Hand-constructed
     * DatasetRow values bypass that check, so guard defensively.
     *
     * <p>The {@code NullVal} / {@code ListVal} / {@code BlockVal} cases
     * don't need to read the bound variable, so they're checked via
     * {@code instanceof} before the value-using switch. Java 21 standard
     * pattern matching requires a variable binding on every {@code case}
     * label; routing the no-binding cases out keeps the switch tidy and
     * sidesteps CodeQL's "unused local variable" check.
     */
    static void writeCellValue(StringBuilder sb, Ast.Value v) {
        if (v instanceof Ast.NullVal) {
            sb.append("null");
            return;
        }
        if (v instanceof Ast.ListVal || v instanceof Ast.BlockVal) {
            throw new IllegalArgumentException(
                    "BindRow: unexpected " + (v instanceof Ast.ListVal ? "list" : "block")
                            + " value in cell (v1 @dataset cells are scalar-shaped)");
        }
        switch (v) {
            case Ast.StringVal s ->
                sb.append('"').append(Format.escape(s.value())).append('"');
            case Ast.IntVal i -> sb.append(i.raw());
            case Ast.FloatVal f -> sb.append(f.raw());
            case Ast.BoolVal b -> sb.append(b.value() ? "true" : "false");
            case Ast.BytesVal by ->
                sb.append("b\"").append(Base64.getEncoder().encodeToString(by.value())).append('"');
            case Ast.IdentVal id -> sb.append(id.name());
            case Ast.TimestampVal t -> sb.append(t.raw());
            case Ast.DurationVal d -> sb.append(d.raw());
            default -> throw new IllegalArgumentException(
                    "BindRow: unexpected cell value type " + v.getClass().getSimpleName());
        }
    }
}
