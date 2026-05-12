// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * AST node types for PXF documents. Sealed hierarchies of records, navigated via pattern matching.
 */
public final class Ast {
    private Ast() {}

    public record Comment(Position pos, String text) {}

    /**
     * Root node of a PXF document.
     *
     * @param typeUrl         body's message type from {@code @type}; may be empty
     * @param directives      side-channel {@code @<name>} directives at document
     *                        root in source order (excludes {@code @type} and
     *                        {@code @table})
     * @param tables          {@code @table} directives in source order. Per draft
     *                        §3.4.4 a document with any table MUST NOT also have
     *                        a {@code typeUrl} or body entries; the parser
     *                        enforces this
     * @param entries         message body entries
     * @param leadingComments comments before any directive or body entry
     */
    public record Document(
            String typeUrl,
            List<Directive> directives,
            List<TableDirective> tables,
            List<Entry> entries,
            List<Comment> leadingComments) {

        public static Document of(String typeUrl, List<Entry> entries) {
            return new Document(typeUrl, List.of(), List.of(), List.copyOf(entries), List.of());
        }
    }

    /**
     * A top-of-document {@code @<name> *(<prefix-id>) [{ ... }]} entry.
     * Carries side-channel metadata for consumer interpretation; the body's
     * schema layer does not see directives.
     *
     * <p>The prefix-identifier list is positional and per-directive. The
     * spec defines two registrations:
     * <ul>
     *   <li>One prefix identifier (the v0.72.0 conventional shape) — names
     *       the inner block's message type. Used by {@code @header} and
     *       similar.</li>
     *   <li>{@code @entry} (draft §3.4.3) — zero, one, or two prefix
     *       identifiers (label, type); a single prefix is disambiguated by
     *       the presence of a {@code .} (dotted ⇒ type; bare ⇒ label).</li>
     * </ul>
     *
     * @param prefixes identifiers between {@code @<name>} and the optional
     *                 {@code {}} block, in source order
     * @param type     legacy compatibility field — populated only when
     *                 {@code prefixes.size() == 1}; otherwise empty. New
     *                 code should read {@link #prefixes()} directly
     * @param body     raw bytes between the opening {@code {} and matching
     *                 {@code }} (both exclusive); null when the directive
     *                 has no inline block
     */
    public record Directive(
            Position pos,
            String name,
            List<String> prefixes,
            String type,
            byte[] body,
            List<Comment> leadingComments) {}

    /**
     * A {@code @table <type> ( col1, col2, ... ) row*} directive at document
     * root (draft §3.4.4). Carries many instances of one message type — the
     * protowire-native CSV replacement.
     *
     * <p>v1 cell-grammar restrictions enforced by the parser: cells exclude
     * list and block values; column entries are unqualified field names
     * (no dotted paths); row arity equals column count; documents with any
     * {@code @table} MUST NOT carry {@code @type} or body field entries.
     */
    public record TableDirective(
            Position pos,
            String type,
            List<String> columns,
            List<TableRow> rows,
            List<Comment> leadingComments) {}

    /**
     * One parenthesized cell tuple in a {@link TableDirective}. The cells
     * list has the same length as the containing table's column list.
     * A {@code null} entry in cells denotes an absent field (the "empty
     * cell" between two commas); a {@link NullVal} denotes a present-but-
     * null field; any other {@link Value} denotes a present field with that
     * value.
     */
    public record TableRow(Position pos, List<Value> cells) {}

    /** A single entry inside a message body: assignment, map entry, or block. */
    public sealed interface Entry permits Assignment, MapEntry, Block {
        Position pos();
        List<Comment> leadingComments();
        String trailingComment();
    }

    public record Assignment(
            Position pos,
            String key,
            Value value,
            List<Comment> leadingComments,
            String trailingComment) implements Entry {}

    public record MapEntry(
            Position pos,
            String key,
            Value value,
            List<Comment> leadingComments,
            String trailingComment) implements Entry {}

    public record Block(
            Position pos,
            String name,
            List<Entry> entries,
            List<Comment> leadingComments,
            String trailingComment) implements Entry {}

    /** Right-hand-side value. */
    public sealed interface Value permits StringVal, IntVal, FloatVal, BoolVal, BytesVal, NullVal,
            IdentVal, TimestampVal, DurationVal, ListVal, BlockVal {
        Position pos();
    }

    public record StringVal(Position pos, String value) implements Value {}
    public record IntVal(Position pos, String raw) implements Value {}
    public record FloatVal(Position pos, String raw) implements Value {}
    public record BoolVal(Position pos, boolean value) implements Value {}
    public record BytesVal(Position pos, byte[] value) implements Value {}
    public record NullVal(Position pos) implements Value {}
    public record IdentVal(Position pos, String name) implements Value {}
    public record TimestampVal(Position pos, Instant value, String raw) implements Value {}
    public record DurationVal(Position pos, Duration value, String raw) implements Value {}
    public record ListVal(Position pos, List<Value> elements) implements Value {}
    public record BlockVal(Position pos, List<Entry> entries) implements Value {}
}
