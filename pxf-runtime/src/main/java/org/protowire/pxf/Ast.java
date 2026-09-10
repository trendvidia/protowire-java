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
     *                        root in source order (excludes spec-defined
     *                        directives: {@code @type}, {@code @dataset},
     *                        {@code @proto}, {@code @entry})
     * @param datasets        {@code @dataset} directives in source order. Per
     *                        draft §3.4.4 a document with any dataset MUST
     *                        NOT also have a {@code typeUrl} or body entries;
     *                        the parser enforces this
     * @param protos          {@code @proto} directives in source order
     *                        (draft §3.4.5)
     * @param entries         message body entries
     * @param leadingComments comments before any directive or body entry
     */
    public record Document(
            String typeUrl,
            List<Directive> directives,
            List<DatasetDirective> datasets,
            List<ProtoDirective> protos,
            List<Entry> entries,
            List<Comment> leadingComments) {

        public static Document of(String typeUrl, List<Entry> entries) {
            return new Document(typeUrl, List.of(), List.of(), List.of(), List.copyOf(entries), List.of());
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
     * A {@code @dataset <type> ( col1, col2, ... ) row*} directive at
     * document root (draft §3.4.4). Carries many instances of one message
     * type — the protowire-native CSV replacement.
     *
     * <p>v1 cell-grammar restrictions enforced by the parser: cells exclude
     * list and block values; column entries are unqualified field names
     * (no dotted paths); row arity equals column count; documents with any
     * {@code @dataset} MUST NOT carry {@code @type} or body field entries.
     *
     * <p>{@code type} MAY be empty when an anonymous {@code @proto}
     * directive (Section 3.4.5) precedes the dataset in document order;
     * the anonymous schema is consumed as the row message type.
     */
    public record DatasetDirective(
            Position pos,
            String type,
            List<String> columns,
            List<DatasetRow> rows,
            List<Comment> leadingComments) {}

    /**
     * One parenthesized cell tuple in a {@link DatasetDirective}. The cells
     * list has the same length as the containing dataset's column list.
     * A {@code null} entry in cells denotes an absent field (the "empty
     * cell" between two commas); a {@link NullVal} denotes a present-but-
     * null field; any other {@link Value} denotes a present field with that
     * value.
     */
    public record DatasetRow(Position pos, List<Value> cells) {}

    /**
     * Shape of a {@link ProtoDirective}'s body (draft §3.4.5).
     */
    public enum ProtoShape {
        /** {@code @proto { <message-body> }} — defines an unnamed message. */
        ANONYMOUS,
        /** {@code @proto <dotted-name> { <message-body> }} — single named message. */
        NAMED,
        /** {@code @proto """<proto-source>"""} — complete .proto source file. */
        SOURCE,
        /** {@code @proto b"<base64-FileDescriptorSet>"} — compiled descriptor. */
        DESCRIPTOR;
    }

    /**
     * A {@code @proto <body>} directive at document root (draft §3.4.5).
     * Carries an embedded protobuf schema, making the PXF document
     * self-describing. The shape distinguishes the four lexically-determined
     * body forms.
     *
     * <p>{@code body} carries raw bytes per shape:
     * <ul>
     *   <li>{@link ProtoShape#ANONYMOUS}, {@link ProtoShape#NAMED}: bytes
     *       between the opening {@code {} and matching {@code }} (both
     *       exclusive). The bytes are protobuf message-body source.</li>
     *   <li>{@link ProtoShape#SOURCE}: contents of the triple-quoted string
     *       (with leading-LF / dedent applied). The bytes are a complete
     *       {@code .proto} source file.</li>
     *   <li>{@link ProtoShape#DESCRIPTOR}: base64-decoded bytes of the
     *       bytes literal. The bytes are a serialised
     *       {@code google.protobuf.FileDescriptorSet}.</li>
     * </ul>
     *
     * <p>{@code typeName} is non-empty only when {@code shape} is
     * {@link ProtoShape#NAMED}.
     */
    public record ProtoDirective(
            Position pos,
            ProtoShape shape,
            String typeName,
            byte[] body,
            List<Comment> leadingComments) {}

    /** A single entry inside a message body: assignment, map entry, or block. */
    public sealed interface Entry permits Assignment, MapEntry, Block {
        Position pos();
        List<Comment> leadingComments();
        String trailingComment();
    }

    /**
     * A {@code key = value} entry.
     *
     * @param keyQuoted whether the document wrote the key as a string
     *     literal ({@code "us-east-1" = { … }}). The grammar admits a
     *     string at entry-name position everywhere ({@code field_entry =
     *     (identifier | string), (assignment_tail | block_tail)}, draft -01
     *     §3.13); the schema layer restricts it to the entries of a keyed
     *     repeated field's block, where the assignment spelling is the
     *     unabbreviated form of a named entry. {@code key} always holds the
     *     unquoted value; the flag preserves the source spelling for
     *     {@link Format}.
     */
    public record Assignment(
            Position pos,
            String key,
            Value value,
            List<Comment> leadingComments,
            String trailingComment,
            boolean keyQuoted) implements Entry {
        /** As the six-argument form with {@code keyQuoted = false}. */
        public Assignment(Position pos, String key, Value value, List<Comment> leadingComments, String trailingComment) {
            this(pos, key, value, leadingComments, trailingComment, false);
        }
    }

    /**
     * A {@code key: value} entry of a map block.
     *
     * @param keyQuoted whether the document wrote the key as a string
     *     literal ({@code "true": …}) rather than bare ({@code true: …}).
     *     The parser records it because the two spellings can denote
     *     different keys — a bare {@code true} is a bool key, a quoted
     *     {@code "true"} the string — and the formatter reproduces the
     *     spelling wherever changing it would change what the key denotes
     *     (draft -01 § Entries and Keys, "Canonical spelling of map keys";
     *     protowire#306, #82). An entry built in code may pass {@code false}
     *     through the five-argument constructor; {@link Format} then picks
     *     the spelling that reads back as the same key.
     */
    public record MapEntry(
            Position pos,
            String key,
            Value value,
            List<Comment> leadingComments,
            String trailingComment,
            boolean keyQuoted) implements Entry {
        /** As the six-argument form with {@code keyQuoted = false}. */
        public MapEntry(Position pos, String key, Value value, List<Comment> leadingComments, String trailingComment) {
            this(pos, key, value, leadingComments, trailingComment, false);
        }
    }

    /**
     * A {@code name { entries }} entry: a nested message, or — inside a
     * keyed repeated field's block — one element, named by its key.
     *
     * @param nameQuoted whether the document wrote the name as a string
     *     literal ({@code "us-east-1" { … }}, draft -01 §3.13). {@code name}
     *     always holds the unquoted (denoted) value; the flag preserves the
     *     source spelling for {@link Format}. A quoted name is only
     *     meaningful as the key of a keyed repeated field's entry — the
     *     schema layer rejects it anywhere else.
     */
    public record Block(
            Position pos,
            String name,
            List<Entry> entries,
            List<Comment> leadingComments,
            String trailingComment,
            boolean nameQuoted) implements Entry {
        /** As the six-argument form with {@code nameQuoted = false}. */
        public Block(Position pos, String name, List<Entry> entries, List<Comment> leadingComments, String trailingComment) {
            this(pos, name, entries, leadingComments, trailingComment, false);
        }
    }

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
