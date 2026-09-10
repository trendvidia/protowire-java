// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.Base64;
import java.util.List;

/**
 * Recursive-descent parser. Produces an {@link Ast.Document}. Comments and blank lines are
 * collected into pending comments and attached to the next entry.
 */
public final class Parser {
    /**
     * Directive names the spec reserves for future allocation (draft
     * §3.4.6). v1 decoders MUST reject these as unknown reserved
     * directives so applications cannot squat the names before the spec
     * allocates semantics to them.
     *
     * <p>The names with their own production ({@code type},
     * {@code dataset}, {@code proto}) don't appear here — they're
     * handled directly by the lexer. The spec-registered {@code entry}
     * doesn't appear either — it's a valid named-directive with
     * documented shape (draft §3.4.3).
     */
    public static final Set<String> FUTURE_RESERVED_DIRECTIVES = Set.of(
            "table", "datasource", "view", "procedure", "function", "permissions");

    private final Lexer lex;
    private Token current;
    /**
     * Current {@code {} / {@code [} nesting, HARDENING.md § Recursion.
     * Top-level entries are depth 0, the first block or list is depth 1,
     * the same convention as protowire-go's {@code parser.go}: the 100th
     * nested block is accepted, the 101st is rejected.
     */
    private int depth;
    private final DecodeLimits limits;
    private final List<Ast.Comment> pendingComments = new ArrayList<>();

    private Parser(byte[] input) {
        this(input, DecodeLimits.defaults());
    }

    private Parser(byte[] input, DecodeLimits limits) {
        // HARDENING.md MaxMessageSize: checked before the first byte is
        // lexed (#79). Of the per-call limits, a parse applies
        // MaxMessageSize, MaxNestingDepth and MaxBytesLiteralLength; the
        // others are about binding to a schema, which a parse does not do.
        if (input.length > limits.maxMessageSize()) {
            throw new PxfException(new Position(1, 1, 0), Limits.messageSizeError(input.length, limits.maxMessageSize()));
        }
        this.limits = limits;
        this.lex = new Lexer(input, limits.maxBytesLiteralLength());
        advance();
    }

    /** Parse a UTF-8 byte buffer of PXF text into an AST. */
    public static Ast.Document parse(byte[] input) {
        return new Parser(input).parseDocument();
    }

    /** Parse under per-call limits (draft -01 § Mandatory Limits). */
    public static Ast.Document parse(byte[] input, DecodeLimits limits) {
        return new Parser(input, limits).parseDocument();
    }

    public static Ast.Document parse(String input) {
        return parse(input.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parse a single {@code ( cell, cell, ... )} tuple as a {@code @dataset}
     * row. Used by {@link DatasetReader} to decode each row's byte slice
     * without re-running the full document grammar. {@code input} MUST
     * start with {@code (} and contain a balanced row tuple.
     *
     * @param input    row bytes including the surrounding parens
     * @param expected expected cell count (column arity)
     */
    static Ast.DatasetRow parseDatasetRow(byte[] input, int expected) {
        return new Parser(input).parseDatasetRow(expected);
    }

    private void advance() {
        while (true) {
            current = lex.next();
            if (current.kind() == TokenKind.NEWLINE) continue;
            if (current.kind() == TokenKind.COMMENT) {
                pendingComments.add(new Ast.Comment(current.pos(), current.value()));
                continue;
            }
            return;
        }
    }

    private List<Ast.Comment> flushComments() {
        if (pendingComments.isEmpty()) return List.of();
        List<Ast.Comment> out = List.copyOf(pendingComments);
        pendingComments.clear();
        return out;
    }

    private Ast.Document parseDocument() {
        List<Ast.Comment> leading = flushComments();
        String typeUrl = "";
        List<Ast.Directive> directives = new ArrayList<>();
        List<Ast.DatasetDirective> datasets = new ArrayList<>();
        List<Ast.ProtoDirective> protos = new ArrayList<>();

        // Top-of-document directives. @type, @<name>, @dataset, and @proto
        // may interleave in any order; @type populates typeUrl, others
        // append to their respective lists.
        directives:
        while (true) {
            switch (current.kind()) {
                case AT_TYPE -> {
                    advance();
                    if (current.kind() != TokenKind.IDENT) {
                        throw new PxfException(current.pos(),
                                "expected type name after @type, got " + current.kind());
                    }
                    typeUrl = current.value();
                    advance();
                }
                case AT_DIRECTIVE -> directives.add(parseDirective());
                case AT_DATASET -> datasets.add(parseDatasetDirective());
                case AT_PROTO -> protos.add(parseProtoDirective());
                default -> {
                    break directives;
                }
            }
        }

        // Standalone constraint (draft §3.4.4): a document containing any
        // @dataset directive MUST NOT also carry @type or top-level field
        // entries — the @dataset header IS the document's type declaration.
        if (!datasets.isEmpty()) {
            if (!typeUrl.isEmpty()) {
                throw new PxfException(datasets.get(0).pos(),
                        "@dataset directive cannot coexist with @type; the @dataset header declares the document's type (draft §3.4.4)");
            }
            if (current.kind() != TokenKind.EOF) {
                throw new PxfException(current.pos(),
                        "@dataset directive cannot coexist with top-level field entries; the document's payload is the @dataset rows (draft §3.4.4)");
            }
        }

        List<Ast.Entry> entries = new ArrayList<>();
        while (current.kind() != TokenKind.EOF) {
            // Top-level: only field_entry is allowed. The document represents
            // a proto message, never a map<K,V>; map_entry (`:` form) is
            // reserved for the inside of a `{ ... }` block.
            entries.add(parseEntry(false));
        }
        return new Ast.Document(typeUrl, List.copyOf(directives), List.copyOf(datasets),
                List.copyOf(protos), List.copyOf(entries), leading);
    }

    /**
     * Parse a {@code @<name> *(<prefix-id>) [{ ... }]} directive. The
     * AT_DIRECTIVE token is current on entry (draft §3.4.2).
     *
     * <p>The grammar accepts zero-or-more prefix identifiers between
     * {@code @<name>} and the optional {@code {}} block. Whitespace is
     * insignificant, so we can't end the prefix run at a newline; instead,
     * one-token lookahead disambiguates — an IDENT followed by {@code =}
     * or {@code :} is a body field key, not a directive prefix.
     */
    private Ast.Directive parseDirective() {
        List<Ast.Comment> leading = flushComments();
        Position pp = current.pos();
        String name = current.value();
        if (FUTURE_RESERVED_DIRECTIVES.contains(name)) {
            throw new PxfException(pp,
                    "@" + name + " is a spec-reserved directive name with no v1 semantics (draft §3.4.6)");
        }
        advance(); // consume AT_DIRECTIVE

        List<String> prefixes = new ArrayList<>();
        while (current.kind() == TokenKind.IDENT) {
            TokenKind nextKind = peekKind();
            if (nextKind == TokenKind.EQUALS || nextKind == TokenKind.COLON) {
                // The IDENT is the first body entry's key, not a prefix.
                break;
            }
            prefixes.add(current.value());
            advance();
        }
        // Back-compat: a single prefix populates the legacy `type` field,
        // matching the v0.72.0 single-Type shape.
        String type = prefixes.size() == 1 ? prefixes.get(0) : "";

        byte[] body = null;
        if (current.kind() == TokenKind.LBRACE) {
            int open = current.pos().offset();
            advance();
            // Parse + discard the inner entries to validate well-formedness.
            parseBody();
            int close = lex.findMatchingBrace(open);
            if (close >= 0) {
                body = lex.sliceBytes(open + 1, close);
            }
        }
        return new Ast.Directive(pp, name, List.copyOf(prefixes), type, body, leading);
    }

    /**
     * Parse a {@code @dataset <type> ( col1, col2, ... ) row*} directive.
     * AT_DATASET is current on entry (draft §3.4.4).
     *
     * <p>The row message type MAY be omitted when an anonymous
     * {@code @proto} directive precedes the dataset (draft §3.4.4
     * Anonymous binding).
     */
    private Ast.DatasetDirective parseDatasetDirective() {
        List<Ast.Comment> leading = flushComments();
        Position pp = current.pos();
        advance(); // consume @dataset

        String type = "";
        if (current.kind() == TokenKind.IDENT) {
            type = current.value();
            advance();
        }

        if (current.kind() != TokenKind.LPAREN) {
            throw new PxfException(current.pos(),
                    "expected '(' to start @dataset column list, got " + current.kind());
        }
        advance();

        if (current.kind() != TokenKind.IDENT) {
            throw new PxfException(current.pos(),
                    "@dataset column list must contain at least one field name, got " + current.kind());
        }
        List<String> columns = new ArrayList<>();
        while (true) {
            if (current.kind() != TokenKind.IDENT) {
                throw new PxfException(current.pos(),
                        "expected column field name, got " + current.kind());
            }
            String colName = current.value();
            // v1: column entries are unqualified field names; dotted paths
            // reserved for a future revision.
            if (colName.indexOf('.') >= 0) {
                throw new PxfException(current.pos(),
                        "@dataset column \"" + colName + "\": dotted column paths are not supported in v1 (draft §3.4.4)");
            }
            columns.add(colName);
            advance();
            if (current.kind() == TokenKind.COMMA) {
                advance();
                continue;
            }
            if (current.kind() == TokenKind.RPAREN) break;
            throw new PxfException(current.pos(),
                    "expected ',' or ')' in @dataset column list, got " + current.kind());
        }
        advance(); // consume )

        // Zero or more rows.
        List<Ast.DatasetRow> rows = new ArrayList<>();
        while (current.kind() == TokenKind.LPAREN) {
            rows.add(parseDatasetRow(columns.size()));
        }
        return new Ast.DatasetDirective(pp, type, List.copyOf(columns), List.copyOf(rows), leading);
    }

    private Ast.DatasetRow parseDatasetRow(int expected) {
        Position pp = current.pos();
        advance(); // consume (

        List<Ast.Value> cells = new ArrayList<>();
        cells.add(parseRowCell());
        while (current.kind() == TokenKind.COMMA) {
            advance();
            cells.add(parseRowCell());
        }
        if (current.kind() != TokenKind.RPAREN) {
            throw new PxfException(current.pos(),
                    "expected ',' or ')' in @dataset row, got " + current.kind());
        }
        advance();
        if (cells.size() != expected) {
            throw new PxfException(pp,
                    "@dataset row has " + cells.size() + " cells, expected " + expected + " (column count)");
        }
        // Row cells legitimately contain null for empty cells. List.copyOf
        // rejects nulls, so wrap an ArrayList copy via Collections instead.
        return new Ast.DatasetRow(pp, java.util.Collections.unmodifiableList(new ArrayList<>(cells)));
    }

    /**
     * Parse a {@code @proto <body>} directive (draft §3.4.5). AT_PROTO is
     * current on entry. Four body shapes are lexically distinguished:
     * anonymous ({@code { ... }}), named ({@code <dotted-name> { ... }}),
     * source-form ({@code """..."""}) and descriptor ({@code b"..."}).
     *
     * <p>For the brace-bounded shapes the body is captured as raw bytes
     * between {@code {} and the matching {@code }} (both exclusive); the
     * contents are protobuf source and are NOT decoded as PXF entries.
     */
    private Ast.ProtoDirective parseProtoDirective() {
        List<Ast.Comment> leading = flushComments();
        Position pp = current.pos();
        advance(); // consume @proto

        switch (current.kind()) {
            case LBRACE -> {
                byte[] body = captureBraceBody("@proto (anonymous form)");
                return new Ast.ProtoDirective(pp, Ast.ProtoShape.ANONYMOUS, "", body, leading);
            }
            case IDENT -> {
                String typeName = current.value();
                advance();
                if (current.kind() != TokenKind.LBRACE) {
                    throw new PxfException(current.pos(),
                            "expected '{' after @proto " + typeName + ", got " + current.kind());
                }
                byte[] body = captureBraceBody("@proto " + typeName);
                return new Ast.ProtoDirective(pp, Ast.ProtoShape.NAMED, typeName, body, leading);
            }
            case STRING -> {
                byte[] body = current.value().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                advance();
                return new Ast.ProtoDirective(pp, Ast.ProtoShape.SOURCE, "", body, leading);
            }
            case BYTES -> {
                String raw = current.value();
                byte[] decoded;
                try {
                    decoded = java.util.Base64.getDecoder().decode(raw);
                } catch (IllegalArgumentException e1) {
                    try {
                        decoded = java.util.Base64.getUrlDecoder().decode(raw);
                    } catch (IllegalArgumentException e2) {
                        throw new PxfException(current.pos(),
                                "@proto descriptor body: invalid base64: " + e1.getMessage());
                    }
                }
                advance();
                return new Ast.ProtoDirective(pp, Ast.ProtoShape.DESCRIPTOR, "", decoded, leading);
            }
            default -> throw new PxfException(current.pos(),
                    "expected '{', dotted identifier, triple-quoted string, or b\"...\" after @proto, got " + current.kind());
        }
    }

    /**
     * Slice the raw bytes between {@code {} and the matching {@code }}
     * (both exclusive) without decoding the contents as PXF entries.
     * LBRACE is current on entry. Repositions the lexer past the closing
     * {@code }} and primes the parser to that next token.
     */
    private byte[] captureBraceBody(String label) {
        int open = current.pos().offset();
        int close = lex.findMatchingBrace(open);
        if (close < 0) {
            throw new PxfException(current.pos(), label + ": unmatched '{'");
        }
        byte[] body = lex.sliceBytes(open + 1, close);
        lex.repositionTo(close + 1);
        advance(); // prime current token past `}`
        return body;
    }

    /**
     * Consume one cell of a @dataset row. Returns {@code null} for an empty
     * cell (no value between commas, or at row start/end). Rejects list
     * and block values per v1 cell-grammar (draft §3.4.4).
     */
    private Ast.Value parseRowCell() {
        switch (current.kind()) {
            case COMMA, RPAREN -> {
                return null;
            }
            case LBRACKET -> throw new PxfException(current.pos(),
                    "@dataset cells cannot contain list values in v1 (draft §3.4.4)");
            case LBRACE -> throw new PxfException(current.pos(),
                    "@dataset cells cannot contain block values in v1 (draft §3.4.4)");
            default -> { /* fall through */ }
        }
        return parseValue();
    }

    /**
     * Peek the next significant token kind without consuming. Used by
     * parseDirective to disambiguate "this IDENT is a directive prefix"
     * from "this IDENT is a body field key".
     */
    private TokenKind peekKind() {
        Lexer.Mark mark = lex.mark();
        Token saved = current;
        int savedComments = pendingComments.size();
        advance();
        TokenKind k = current.kind();
        // Restore.
        lex.restore(mark);
        current = saved;
        while (pendingComments.size() > savedComments) {
            pendingComments.remove(pendingComments.size() - 1);
        }
        return k;
    }

    private Ast.Entry parseEntry() {
        return parseEntry(true);
    }

    private Ast.Entry parseEntry(boolean allowMapEntry) {
        List<Ast.Comment> leading = flushComments();
        Position pp = current.pos();
        // map-key = identifier / string / integer / bool (draft -01
        // §abnf-grammar; the keyword spelling landed in protowire#284): a
        // bare true / false is a key, and like an integer it takes only
        // the ':' tail — the '=' and '{' arms below insist on an identifier.
        if (current.kind() != TokenKind.IDENT && current.kind() != TokenKind.STRING
                && current.kind() != TokenKind.INT && current.kind() != TokenKind.BOOL) {
            throw new PxfException(pp, "expected identifier, string, integer, or bool, got " + current.kind() + " (\"" + current.value() + "\")");
        }
        TokenKind keyKind = current.kind();
        String key = current.value();
        advance();

        return switch (current.kind()) {
            case EQUALS -> {
                // `=` denotes a field assignment on a proto message: the key
                // is an identifier (= proto field name), or a string — the
                // grammar admits a quoted entry name everywhere (field_entry
                // = (identifier | string), (assignment_tail | block_tail),
                // draft -01 §3.13); the schema layer restricts it to keyed
                // repeated fields' blocks. Integer / bool keys are map keys
                // and take only ':'. See docs/grammar.ebnf → field_entry.
                if (keyKind != TokenKind.IDENT && keyKind != TokenKind.STRING) {
                    throw new PxfException(pp,
                            "field assignment with '=' requires an identifier or string key, got " + keyKind
                                    + " (\"" + key + "\"); use ':' for map entries");
                }
                advance();
                Ast.Value v = parseValue();
                yield new Ast.Assignment(pp, key, v, leading, "", keyKind == TokenKind.STRING);
            }
            case COLON -> {
                // Map entry. Only allowed inside a `{ ... }` block, never at
                // document top level. See docs/grammar.ebnf → document.
                if (!allowMapEntry) {
                    throw new PxfException(pp,
                            "map entry (':' form) is only allowed inside a '{ … }' block; "
                                    + "use '=' for top-level field assignments");
                }
                advance();
                Ast.Value v = parseValue();
                yield new Ast.MapEntry(pp, key, v, leading, "", keyKind == TokenKind.STRING);
            }
            case LBRACE -> {
                // `{ ... }` denotes a submessage field, or a named entry of
                // a keyed repeated field; same identifier-or-string rule as
                // `=`. See docs/grammar.ebnf → field_entry.
                if (keyKind != TokenKind.IDENT && keyKind != TokenKind.STRING) {
                    throw new PxfException(pp,
                            "submessage block requires an identifier or string key, got " + keyKind
                                    + " (\"" + key + "\")");
                }
                advance();
                List<Ast.Entry> body = parseBody();
                yield new Ast.Block(pp, key, body, leading, "", keyKind == TokenKind.STRING);
            }
            default -> throw new PxfException(current.pos(),
                    "expected '=', ':', or '{' after \"" + key + "\", got " + current.kind());
        };
    }

    private Ast.Value parseValue() {
        Position pp = current.pos();
        return switch (current.kind()) {
            case STRING -> { var v = new Ast.StringVal(pp, current.value()); advance(); yield v; }
            case INT    -> { var v = new Ast.IntVal(pp, current.value());    advance(); yield v; }
            case FLOAT  -> { var v = new Ast.FloatVal(pp, current.value());  advance(); yield v; }
            case BOOL   -> { var v = new Ast.BoolVal(pp, "true".equals(current.value())); advance(); yield v; }
            case BYTES  -> {
                byte[] decoded;
                try { decoded = Base64.getDecoder().decode(current.value()); }
                catch (IllegalArgumentException e) {
                    decoded = Base64.getDecoder().decode(current.value() + Lexer.padding(current.value()));
                }
                var v = new Ast.BytesVal(pp, decoded);
                advance(); yield v;
            }
            case TIMESTAMP -> {
                var t = TimeFormats.parseRfc3339(current.value());
                var v = new Ast.TimestampVal(pp, t, current.value());
                advance(); yield v;
            }
            case DURATION -> {
                var d = TimeFormats.parseGoDuration(current.value());
                var v = new Ast.DurationVal(pp, d, current.value());
                advance(); yield v;
            }
            case NULL  -> { var v = new Ast.NullVal(pp); advance(); yield v; }
            case IDENT -> { var v = new Ast.IdentVal(pp, current.value()); advance(); yield v; }
            case LBRACKET -> parseList();
            case LBRACE   -> parseBlockVal();
            default -> throw new PxfException(pp, "expected value, got " + current.kind() + " (\"" + current.value() + "\")");
        };
    }

    /** Enters one nesting level or rejects: HARDENING.md § Recursion. */
    private void enter(Position pp) {
        if (++depth > limits.maxNestingDepth()) {
            throw new PxfException(pp, "nesting depth exceeds MaxNestingDepth=" + limits.maxNestingDepth());
        }
    }

    private Ast.Value parseList() {
        enter(current.pos());
        try {
            return parseListInner();
        } finally {
            depth--;
        }
    }

    private Ast.Value parseListInner() {
        Position pp = current.pos();
        advance();
        List<Ast.Value> elems = new ArrayList<>();
        while (current.kind() != TokenKind.RBRACKET && current.kind() != TokenKind.EOF) {
            elems.add(parseValue());
            if (current.kind() == TokenKind.COMMA) advance();
        }
        if (current.kind() != TokenKind.RBRACKET) {
            throw new PxfException(current.pos(), "expected ']', got " + current.kind());
        }
        advance();
        return new Ast.ListVal(pp, List.copyOf(elems));
    }

    private Ast.Value parseBlockVal() {
        Position pp = current.pos();
        advance();
        List<Ast.Entry> body = parseBody();
        return new Ast.BlockVal(pp, body);
    }

    private List<Ast.Entry> parseBody() {
        enter(current.pos());
        try {
            return parseBodyInner();
        } finally {
            depth--;
        }
    }

    private List<Ast.Entry> parseBodyInner() {
        List<Ast.Entry> entries = new ArrayList<>();
        while (current.kind() != TokenKind.RBRACE && current.kind() != TokenKind.EOF) {
            entries.add(parseEntry());
        }
        if (current.kind() != TokenKind.RBRACE) {
            throw new PxfException(current.pos(), "expected '}', got " + current.kind());
        }
        advance();
        return List.copyOf(entries);
    }
}
