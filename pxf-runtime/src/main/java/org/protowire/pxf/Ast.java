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
     * @param trailingComments the comments after the document's last entry,
     *                        directive or dataset row, which precede nothing
     *                        of their own (#200, protowire-go#279)
     * @param bodyOffset      the byte just past the last directive's last
     *                        token, where the body begins; 0 when there is no
     *                        directive. As protowire-go's {@code BodyOffset} (#209)
     */
    public record Document(
            String typeUrl,
            List<Directive> directives,
            List<DatasetDirective> datasets,
            List<ProtoDirective> protos,
            List<Entry> entries,
            List<Comment> leadingComments,
            List<Comment> trailingComments,
            int bodyOffset) {

        /** As the eight-argument form with {@code bodyOffset = 0}. */
        public Document(String typeUrl, List<Directive> directives, List<DatasetDirective> datasets,
                List<ProtoDirective> protos, List<Entry> entries, List<Comment> leadingComments,
                List<Comment> trailingComments) {
            this(typeUrl, directives, datasets, protos, entries, leadingComments, trailingComments, 0);
        }

        /** As the eight-argument form with no trailing comments and {@code bodyOffset = 0}. */
        public Document(String typeUrl, List<Directive> directives, List<DatasetDirective> datasets,
                List<ProtoDirective> protos, List<Entry> entries, List<Comment> leadingComments) {
            this(typeUrl, directives, datasets, protos, entries, leadingComments, List.of(), 0);
        }

        public static Document of(String typeUrl, List<Entry> entries) {
            return new Document(typeUrl, List.of(), List.of(), List.of(), List.copyOf(entries), List.of(), List.of(), 0);
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
     * <p>A cell holds any value, list and block included, and a column may
     * be a dotted path into a sub-message. Enforced by the parser: a header
     * names no path twice, no field beside a path inside it, and no empty
     * segment; row arity equals column count; documents with any
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
     *
     * @param leadingComments the comments before this row (after the column
     *     list or the previous row) and inside it. {@link Format} writes a
     *     row on one line, so it writes them all before the row (#200,
     *     protowire-go#279)
     */
    public record DatasetRow(Position pos, List<Value> cells, List<Comment> leadingComments) {
        /** As the three-argument form with no comments. */
        public DatasetRow(Position pos, List<Value> cells) {
            this(pos, cells, List.of());
        }
    }

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

    /** A single entry inside a message body: assignment, map entry, block, or type entry. */
    public sealed interface Entry permits Assignment, MapEntry, Block, TypeEntry {
        Position pos();

        /**
         * Just past the entry's last byte: its value's end, or a block's
         * closing brace. Its span is {@code pos()} to {@code end()}, as
         * protowire-go's {@code EntrySpan} (#209). An entry built in code has
         * {@code end() == pos()}.
         */
        Position end();
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
     * @param equalsPos where the {@code =} stands. A schema layer that
     *     refuses the assignment because of its {@code =}, such as one
     *     inside a map block, reports it here, as protowire-go does (#222).
     */
    public record Assignment(
            Position pos,
            String key,
            Value value,
            List<Comment> leadingComments,
            String trailingComment,
            boolean keyQuoted,
            Position equalsPos,
            Position end) implements Entry {
        /** As the eight-argument form with {@code end = pos}. */
        public Assignment(Position pos, String key, Value value, List<Comment> leadingComments, String trailingComment,
                boolean keyQuoted, Position equalsPos) {
            this(pos, key, value, leadingComments, trailingComment, keyQuoted, equalsPos, pos);
        }

        /** As the eight-argument form with {@code equalsPos = pos} and {@code end = pos}. */
        public Assignment(Position pos, String key, Value value, List<Comment> leadingComments, String trailingComment,
                boolean keyQuoted) {
            this(pos, key, value, leadingComments, trailingComment, keyQuoted, pos, pos);
        }

        /** As the eight-argument form with {@code keyQuoted = false}, {@code equalsPos = pos} and {@code end = pos}. */
        public Assignment(Position pos, String key, Value value, List<Comment> leadingComments, String trailingComment) {
            this(pos, key, value, leadingComments, trailingComment, false, pos, pos);
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
            boolean keyQuoted,
            Position end) implements Entry {
        /** As the seven-argument form with {@code end = pos}. */
        public MapEntry(Position pos, String key, Value value, List<Comment> leadingComments, String trailingComment,
                boolean keyQuoted) {
            this(pos, key, value, leadingComments, trailingComment, keyQuoted, pos);
        }

        /** As the seven-argument form with {@code keyQuoted = false} and {@code end = pos}. */
        public MapEntry(Position pos, String key, Value value, List<Comment> leadingComments, String trailingComment) {
            this(pos, key, value, leadingComments, trailingComment, false, pos);
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
     * @param bracePos where the opening brace stands. A schema layer
     *     that refuses the block because of its brace, such as one inside a
     *     map block, reports it here, as protowire-go does (#222).
     * @param trailingComments the comments after the last entry, before the
     *     closing brace (all of an empty block's comments), which precede no
     *     entry (#200, protowire-go#279). Not to be confused with
     *     {@code trailingComment}, the comment on an entry's own line.
     */
    public record Block(
            Position pos,
            String name,
            List<Entry> entries,
            List<Comment> leadingComments,
            String trailingComment,
            boolean nameQuoted,
            Position bracePos,
            List<Comment> trailingComments,
            Position end) implements Entry {
        /** As the nine-argument form with {@code end = pos}. */
        public Block(Position pos, String name, List<Entry> entries, List<Comment> leadingComments, String trailingComment,
                boolean nameQuoted, Position bracePos, List<Comment> trailingComments) {
            this(pos, name, entries, leadingComments, trailingComment, nameQuoted, bracePos, trailingComments, pos);
        }

        /** As the nine-argument form with no trailing comments and {@code end = pos}. */
        public Block(Position pos, String name, List<Entry> entries, List<Comment> leadingComments, String trailingComment,
                boolean nameQuoted, Position bracePos) {
            this(pos, name, entries, leadingComments, trailingComment, nameQuoted, bracePos, List.of(), pos);
        }

        /** As the nine-argument form with {@code bracePos = pos}, no trailing comments and {@code end = pos}. */
        public Block(Position pos, String name, List<Entry> entries, List<Comment> leadingComments, String trailingComment,
                boolean nameQuoted) {
            this(pos, name, entries, leadingComments, trailingComment, nameQuoted, pos, List.of(), pos);
        }

        /** As the nine-argument form with {@code nameQuoted = false}, {@code bracePos = pos}, no trailing comments and {@code end = pos}. */
        public Block(Position pos, String name, List<Entry> entries, List<Comment> leadingComments, String trailingComment) {
            this(pos, name, entries, leadingComments, trailingComment, false, pos, List.of(), pos);
        }
    }

    /**
     * The {@code @type = "<type-url>"} entry that may open a block bound to
     * {@code google.protobuf.Any} (draft -01 "Any Blocks", protowire#354). A
     * parser has no schema and cannot tell which blocks are Any, so it accepts
     * this as the first entry of any block, and the decoder rejects it on a
     * block that is not one.
     *
     * @param urlPos where the URL's string literal stands. A decoder that
     *     cannot resolve the URL reports it here, as protowire-go does (#222).
     */
    public record TypeEntry(
            Position pos,
            String url,
            List<Comment> leadingComments,
            String trailingComment,
            Position urlPos,
            Position end) implements Entry {
        /** As the six-argument form with {@code end = pos}. */
        public TypeEntry(Position pos, String url, List<Comment> leadingComments, String trailingComment, Position urlPos) {
            this(pos, url, leadingComments, trailingComment, urlPos, pos);
        }

        /** As the six-argument form with {@code urlPos = pos} and {@code end = pos}. */
        public TypeEntry(Position pos, String url, List<Comment> leadingComments, String trailingComment) {
            this(pos, url, leadingComments, trailingComment, pos, pos);
        }
    }

    /** Right-hand-side value. */
    public sealed interface Value permits StringVal, IntVal, FloatVal, BoolVal, BytesVal, NullVal,
            IdentVal, TimestampVal, DurationVal, ListVal, BlockVal, BadVal {
        Position pos();

        /**
         * Just past the value's last byte, as protowire-go's
         * {@code ValueSpan} (#209). A value built in code has
         * {@code end() == pos()}, and so does a {@link BadVal}, which spans
         * no source bytes.
         */
        Position end();
    }

    /**
     * A placeholder for a value that was required but missing or malformed,
     * as protowire-go's {@code BadVal} (#208). Only {@link Parser#parseTolerant}
     * makes one; {@link Parser#parse} never does. For a missing value,
     * {@code pos} is just past the token that required it (a dangling
     * {@code =} or key), where the value would be typed; for a malformed
     * literal, it is the literal's own position. The error is among those
     * the tolerant parse returns. {@link Format} writes a BadVal as nothing,
     * so a formatted tree that holds one may not parse again, and a decoder
     * refuses it.
     */
    public record BadVal(Position pos) implements Value {
        /** A BadVal spans no source bytes. */
        @Override public Position end() { return pos; }
    }

    public record StringVal(Position pos, String value, Position end) implements Value {
        /** As the three-argument form with {@code end = pos}. */
        public StringVal(Position pos, String value) { this(pos, value, pos); }
    }

    /**
     * An integer literal.
     *
     * @param prec the digits of its precision suffix ({@code 64} for
     *     {@code 5p64}), or "". The grammar takes one on any number; only a
     *     {@code pxf.BigFloat} reads it, and the schema layer rejects it on
     *     any other field (draft -01 § PXF Annotations, protowire#406).
     */
    public record IntVal(Position pos, String raw, String prec, Position end) implements Value {
        /** As the four-argument form with {@code end = pos}. */
        public IntVal(Position pos, String raw, String prec) { this(pos, raw, prec, pos); }

        /** As the four-argument form with no suffix and {@code end = pos}. */
        public IntVal(Position pos, String raw) { this(pos, raw, "", pos); }

        /** The literal as written, its suffix included. */
        public String literal() { return prec.isEmpty() ? raw : raw + "p" + prec; }
    }

    /**
     * A floating-point literal.
     *
     * @param prec the digits of its precision suffix, or "": see {@link IntVal}
     */
    public record FloatVal(Position pos, String raw, String prec, Position end) implements Value {
        /** As the four-argument form with {@code end = pos}. */
        public FloatVal(Position pos, String raw, String prec) { this(pos, raw, prec, pos); }

        /** As the four-argument form with no suffix and {@code end = pos}. */
        public FloatVal(Position pos, String raw) { this(pos, raw, "", pos); }

        /** The literal as written, its suffix included. */
        public String literal() { return prec.isEmpty() ? raw : raw + "p" + prec; }
    }

    public record BoolVal(Position pos, boolean value, Position end) implements Value {
        public BoolVal(Position pos, boolean value) { this(pos, value, pos); }
    }

    public record BytesVal(Position pos, byte[] value, Position end) implements Value {
        public BytesVal(Position pos, byte[] value) { this(pos, value, pos); }
    }

    public record NullVal(Position pos, Position end) implements Value {
        public NullVal(Position pos) { this(pos, pos); }
    }

    public record IdentVal(Position pos, String name, Position end) implements Value {
        public IdentVal(Position pos, String name) { this(pos, name, pos); }
    }

    public record TimestampVal(Position pos, Instant value, String raw, Position end) implements Value {
        public TimestampVal(Position pos, Instant value, String raw) { this(pos, value, raw, pos); }
    }

    public record DurationVal(Position pos, Duration value, String raw, Position end) implements Value {
        public DurationVal(Position pos, Duration value, String raw) { this(pos, value, raw, pos); }
    }

    public record ListVal(Position pos, List<Value> elements, Position end) implements Value {
        public ListVal(Position pos, List<Value> elements) { this(pos, elements, pos); }
    }
    /**
     * A {@code { entries }} value.
     *
     * @param trailingComments the comments after the last entry, before the
     *     closing brace (all of an empty block's comments), which precede no
     *     entry (#200, protowire-go#279)
     */
    public record BlockVal(Position pos, List<Entry> entries, List<Comment> trailingComments, Position end) implements Value {
        /** As the four-argument form with {@code end = pos}. */
        public BlockVal(Position pos, List<Entry> entries, List<Comment> trailingComments) {
            this(pos, entries, trailingComments, pos);
        }

        /** As the four-argument form with no trailing comments and {@code end = pos}. */
        public BlockVal(Position pos, List<Entry> entries) {
            this(pos, entries, List.of(), pos);
        }
    }
}
