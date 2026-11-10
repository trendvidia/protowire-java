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
    /** Just past the token {@link #advance} consumed last: where the value it ended ends. */
    private Position prevEnd = Position.UNKNOWN;
    /**
     * The error-recovering mode of {@link #parseTolerant}: where a strict
     * parse throws its first error, this one records it in {@link #errs} and
     * resynchronizes at the nearest entry or block boundary, as protowire-go's
     * tolerant parser does (#208). Every recovery is marked at its site; when
     * it is off the parse is what it always was.
     */
    private final boolean tolerant;
    private final List<PxfException> errs = new ArrayList<>();

    private Parser(byte[] input) {
        this(input, DecodeLimits.defaults());
    }

    private Parser(byte[] input, DecodeLimits limits) {
        this(input, limits, new Position(1, 1, 0));
    }

    private Parser(byte[] input, DecodeLimits limits, Position start) {
        this(input, limits, start, false);
    }

    private Parser(byte[] input, DecodeLimits limits, Position start, boolean tolerant) {
        // HARDENING.md MaxMessageSize: checked before the first byte is
        // lexed (#79). Of the per-call limits, a parse applies
        // MaxMessageSize, MaxNestingDepth and MaxBytesLiteralLength; the
        // others are about binding to a schema, which a parse does not do.
        if (input.length > limits.maxMessageSize()) {
            throw new PxfException(new Position(1, 1, 0), Limits.messageSizeError(input.length, limits.maxMessageSize()));
        }
        this.limits = limits;
        this.tolerant = tolerant;
        this.lex = new Lexer(input, limits.maxBytesLiteralLength());
        this.lex.startAt(start);
        if (tolerant) {
            lex.tolerant = true;
            lex.onErr = (pos, msg) -> errs.add(new PxfException(pos, msg));
        }
        advance();
    }

    /**
     * A parser continuing a decoder's token stream, so a {@code @dataset}
     * cell decoded on the fused path needs no second value grammar. {@code
     * depth} is the caller's nesting depth.
     */
    Parser(Lexer lex, Token current, DecodeLimits limits, int depth) {
        this.lex = lex;
        this.current = current;
        this.limits = limits;
        this.depth = depth;
        this.tolerant = false;
    }

    /** The token after the last one consumed; the caller resumes from it. */
    Token current() { return current; }

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
     * What {@link #parseTolerant} returns: the document, never null, and
     * every error, in source order.
     */
    public record TolerantResult(Ast.Document document, List<PxfException> errors) {}

    /**
     * As {@link #parseTolerant(byte[], DecodeLimits)} under the default limits.
     */
    public static TolerantResult parseTolerant(byte[] input) {
        return parseTolerant(input, DecodeLimits.defaults());
    }

    /**
     * Parses a document that may be broken, for editor tooling that needs a
     * tree exactly when the buffer is broken (completion mid-keystroke, hover
     * on a half-typed entry), as protowire-go's {@code ParseTolerant} (#208).
     * Where {@link #parse} stops at the first syntax error, this recovers at
     * entry and block boundaries and returns the tree it could build with
     * every error, in source order. A valid document gives the tree
     * {@link #parse} gives, and no error.
     *
     * <p>It recovers as Go does:
     * <ul>
     *   <li>a missing or malformed value ({@code key =} at the end of a line,
     *       or {@code key} alone) becomes an {@link Ast.BadVal}, so the entry
     *       and its key are in the tree;</li>
     *   <li>an unclosed block or list is closed at the end, with what it
     *       holds, or, inside a {@code @dataset} cell, at the row's
     *       {@code )}, so the rows after it still parse;</li>
     *   <li>an unterminated string ends at the newline or the end; an
     *       unterminated triple-quoted string, bytes literal or block comment
     *       at the end, a bytes literal at the newline;</li>
     *   <li>a malformed directive is skipped to the next directive or body
     *       entry.</li>
     * </ul>
     *
     * <p>The tree is for tooling, not for decoding: it may hold
     * {@link Ast.BadVal}s. Decode once the document is valid.
     */
    public static TolerantResult parseTolerant(byte[] input, DecodeLimits limits) {
        if (input.length > limits.maxMessageSize()) {
            return new TolerantResult(Ast.Document.of("", List.of()), List.of(new PxfException(new Position(1, 1, 0),
                    Limits.messageSizeError(input.length, limits.maxMessageSize()))));
        }
        Parser p = new Parser(input, limits, new Position(1, 1, 0), true);
        Ast.Document doc = p.parseDocument();
        // Recorded in parse order, which is source order except where the
        // lexer reports during a lookahead; sorted, as protowire-go sorts
        // them. List.sort is stable.
        List<PxfException> errs = new ArrayList<>(p.errs);
        errs.sort(java.util.Comparator.comparingInt(e -> e.position().offset()));
        return new TolerantResult(doc, List.copyOf(errs));
    }

    /** A recoverable error: a strict parse throws it, a tolerant one records it and goes on. */
    private void soft(PxfException e) {
        if (!tolerant) throw e;
        errs.add(e);
    }

    /** Whether a tolerant parse closes the list or block it is in at a ')': inside a value that is the end of the @dataset row the value is a cell of. */
    private boolean tolerantRowClose() {
        return tolerant && current.kind() == TokenKind.RPAREN;
    }

    /**
     * Consumes the open token the parser is on and everything through its
     * matching close (or the end). The tolerant recovery for a block or list
     * past MaxNestingDepth, which it cannot descend into.
     */
    private void skipBalanced(TokenKind open, TokenKind close) {
        int d = 0;
        while (true) {
            if (current.kind() == open) {
                d++;
            } else if (current.kind() == close) {
                if (--d <= 0) {
                    advance();
                    return;
                }
            } else if (current.kind() == TokenKind.EOF) {
                return;
            }
            advance();
        }
    }

    /**
     * Whether the current token is the key of a new entry: a key-shaped token
     * followed by '=', ':' or '{'. A tolerant parse takes such a token as the
     * next entry's, not part of what it is parsing.
     */
    private boolean currentStartsEntry() {
        return switch (current.kind()) {
            case IDENT, STRING, INT, BOOL -> {
                TokenKind next = peekKind();
                yield next == TokenKind.EQUALS || next == TokenKind.COLON || next == TokenKind.LBRACE;
            }
            default -> false;
        };
    }

    /** Advances to the next directive, token that can start a body entry, or the end: the recovery after a malformed directive. */
    private void syncTopLevel() {
        while (true) {
            switch (current.kind()) {
                case EOF, AT_TYPE, AT_DIRECTIVE, AT_DATASET, AT_PROTO, IDENT, STRING, INT -> {
                    return;
                }
                default -> advance();
            }
        }
    }

    /**
     * Parse a single {@code ( cell, cell, ... )} tuple as a {@code @dataset}
     * row. Used by {@link DatasetReader} to decode each row's byte slice
     * without re-running the full document grammar. {@code input} MUST
     * hold only newlines and comments before the row's {@code (}, and a
     * balanced row tuple. The comments are the row's leading comments.
     *
     * @param input    row bytes including the surrounding parens
     * @param expected expected cell count (column arity)
     * @param start    where {@code input} begins in its document, so a row's
     *                 positions and errors are the document's
     */
    static Ast.DatasetRow parseDatasetRow(byte[] input, int expected, Position start) {
        Parser p = new Parser(input, DecodeLimits.defaults(), start);
        return p.parseDatasetRow(expected);
    }

    /**
     * Takes the pending comment that trails a value on its own line, as
     * {@code b = 1 # tc}, for the entry that value ends (#193). {@code end} is
     * where the value ends. A newline ends every line, so at most one comment
     * trails a value, and it is the first pending one. A comment on a later
     * line is the next entry's leading comment, and stays pending. Without
     * this, the comment surfaced as the next entry's leading comment, or was
     * lost before a block's '}' or the document's end. protowire-go's
     * takeTrailingComment.
     */
    private String takeTrailingComment(Position end) {
        if (pendingComments.isEmpty()) return "";
        Ast.Comment c = pendingComments.get(0);
        if (c.pos().line() != end.line() || c.pos().offset() < end.offset()) return "";
        pendingComments.remove(0);
        return c.text();
    }

    private void advance() {
        prevEnd = lex.position();
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
                    // A dangling @type with the body right after it: an
                    // identifier followed by '=', ':' or '{' is the first
                    // entry's key, not the type name.
                    if (current.kind() != TokenKind.IDENT || (tolerant && currentStartsEntry())) {
                        soft(new PxfException(current.pos(), "expected type name after @type, got " + current.kind()));
                        syncTopLevel();
                        continue;
                    }
                    typeUrl = current.value();
                    advance();
                }
                case AT_DIRECTIVE, AT_DATASET, AT_PROTO -> {
                    // A malformed directive: a tolerant parse records it and
                    // skips to the next directive or body entry.
                    try {
                        switch (current.kind()) {
                            case AT_DIRECTIVE -> directives.add(parseDirective());
                            case AT_DATASET -> datasets.add(parseDatasetDirective());
                            default -> protos.add(parseProtoDirective());
                        }
                    } catch (PxfException e) {
                        soft(e);
                        syncTopLevel();
                    }
                }
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
                soft(new PxfException(datasets.get(0).pos(),
                        "@dataset directive cannot coexist with @type; the @dataset header declares the document's type (draft §3.4.4)"));
            }
            if (current.kind() != TokenKind.EOF) {
                soft(new PxfException(current.pos(),
                        "@dataset directive cannot coexist with top-level field entries; the document's payload is the @dataset rows (draft §3.4.4)"));
            }
        }

        List<Ast.Entry> entries = new ArrayList<>();
        while (current.kind() != TokenKind.EOF) {
            // Top-level: only field_entry is allowed. The document represents
            // a proto message, never a map<K,V>; map_entry (`:` form) is
            // reserved for the inside of a `{ ... }` block. A tolerant parse
            // gives null for an entry it skipped past.
            Ast.Entry e = parseEntry(false);
            if (e != null) entries.add(e);
        }
        // The comments after the last entry, directive or row, which no
        // entry follows to take them (#200).
        List<Ast.Comment> trailing = flushComments();
        return new Ast.Document(typeUrl, List.copyOf(directives), List.copyOf(datasets),
                List.copyOf(protos), List.copyOf(entries), leading, trailing);
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
            // A tolerant parse records it and reads the directive on, so the
            // rest of the document still has a tree.
            soft(new PxfException(pp,
                    "@" + name + " is a spec-reserved directive name with no v1 semantics (draft §3.4.6)"));
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
            // Parse + discard the inner entries to validate well-formedness.
            parseBlockVal();
            int close = lex.findMatchingBrace(open);
            if (close < 0) {
                // Only a tolerant parse gets here, after closing an unclosed
                // block at the end.
                throw new PxfException(pp, "directive @" + name + ": unmatched '{'");
            }
            body = lex.sliceBytes(open + 1, close);
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
        List<Position> colPos = new ArrayList<>();
        while (true) {
            if (current.kind() != TokenKind.IDENT) {
                throw new PxfException(current.pos(),
                        "expected column field name, got " + current.kind());
            }
            columns.add(current.value());
            colPos.add(current.pos());
            advance();
            if (current.kind() == TokenKind.COMMA) {
                advance();
                continue;
            }
            if (current.kind() == TokenKind.RPAREN) break;
            throw new PxfException(current.pos(),
                    "expected ',' or ')' in @dataset column list, got " + current.kind());
        }
        // The path rules, once the list is read, as protowire-go checks them.
        for (int i = 0; i < columns.size(); i++) {
            checkColumnPath(columns.subList(0, i), columns.get(i), colPos.get(i));
        }
        advance(); // consume )

        // Zero or more rows.
        List<Ast.DatasetRow> rows = new ArrayList<>();
        while (current.kind() == TokenKind.LPAREN) {
            rows.add(parseDatasetRow(columns.size()));
        }
        return new Ast.DatasetDirective(pp, type, List.copyOf(columns), List.copyOf(rows), leading);
    }

    /**
     * The header rules that need no schema (draft §3.4.4, protowire#342): a
     * path has no empty segment, names no column twice, and no column is a
     * prefix of another, since a row could then set one sub-message twice.
     * The rules that need the row type are {@code BindRow}'s, when a row
     * binds. The lexer keeps {@code .} in an identifier, so a path is one
     * token here. Mirrors protowire-go's {@code columnPathError}.
     */
    static void checkColumnPath(List<String> earlier, String col, Position pos) {
        String problem = columnPathProblem(earlier, col);
        if (problem != null) throw new PxfException(pos, problem);
    }

    /** {@link #checkColumnPath}'s rules: what is wrong with {@code col} after {@code earlier}, or null. */
    static String columnPathProblem(List<String> earlier, String col) {
        if (col.startsWith(".") || col.endsWith(".") || col.contains("..")) {
            return "@dataset column \"" + col + "\": a column path has an empty segment (draft §3.4.4)";
        }
        for (String prev : earlier) {
            if (prev.equals(col)) {
                return "@dataset column \"" + col + "\" appears twice (draft §3.4.4)";
            }
            if (col.startsWith(prev + ".") || prev.startsWith(col + ".")) {
                return "@dataset column \"" + col + "\" overlaps column \"" + prev
                        + "\": a field and a path inside it cannot both be columns (draft §3.4.4)";
            }
        }
        return null;
    }

    /**
     * The comments pending at the '(' and those inside the row are the
     * row's leading comments (#200). Left pending, they would pass to the
     * next row or directive, or to the document's end. {@link DatasetReader}
     * parses its rows here too, so it files them as {@link #parse} does.
     */
    private Ast.DatasetRow parseDatasetRow(int expected) {
        Position pp = current.pos();
        List<Ast.Comment> leading = new ArrayList<>(flushComments());
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
        // Taken before the ')' is consumed: advance would add the comments
        // after it, which belong to what follows the row.
        leading.addAll(flushComments());
        advance();
        if (cells.size() != expected) {
            throw new PxfException(pp,
                    "@dataset row has " + cells.size() + " cells, expected " + expected + " (column count)");
        }
        // Row cells legitimately contain null for empty cells. List.copyOf
        // rejects nulls, so wrap an ArrayList copy via Collections instead.
        return new Ast.DatasetRow(pp, java.util.Collections.unmodifiableList(new ArrayList<>(cells)), List.copyOf(leading));
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
                    decoded = Base64Literal.decodeDescriptor(raw);
                } catch (Base64Literal.Invalid e) {
                    throw new PxfException(current.pos(), "@proto descriptor body: invalid base64: " + e.getMessage());
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
     * cell (no value between commas, or at row start/end). A cell holds any
     * value, list and block included (draft §3.4.4, protowire#341).
     */
    Ast.Value parseRowCell() {
        if (current.kind() == TokenKind.COMMA || current.kind() == TokenKind.RPAREN) return null;
        // A block cell's entries flush the pending comments as their own.
        // Those before the cell (between rows, or before the row's first
        // cell) belong to no entry of it, so they are set aside: the cell
        // keeps only the comments written inside it, as protowire-go's does.
        List<Ast.Comment> before = new ArrayList<>(pendingComments);
        pendingComments.clear();
        try {
            return parseValue();
        } finally {
            pendingComments.addAll(0, before);
        }
    }

    /**
     * Peek the next significant token kind without consuming. Used by
     * parseDirective to disambiguate "this IDENT is a directive prefix"
     * from "this IDENT is a body field key".
     */
    private TokenKind peekKind() {
        Lexer.Mark mark = lex.mark();
        Token saved = current;
        Position savedEnd = prevEnd;
        int savedComments = pendingComments.size();
        int savedErrs = errs.size(); // a tolerant lexer may report during the peek
        advance();
        TokenKind k = current.kind();
        // Restore.
        lex.restore(mark);
        current = saved;
        prevEnd = savedEnd;
        while (pendingComments.size() > savedComments) {
            pendingComments.remove(pendingComments.size() - 1);
        }
        while (errs.size() > savedErrs) {
            errs.remove(errs.size() - 1);
        }
        return k;
    }

    private Ast.Entry parseEntry() {
        return parseEntry(true);
    }

    /** An integer key as written, with its precision suffix: protowire-go's writtenKey. */
    private static String writtenKey(String key, String prec) {
        return prec.isEmpty() ? key : key + "p" + Limits.clipLiteral(prec);
    }

    /**
     * Whether a block or list opened at the current token would go past
     * MaxNestingDepth (HARDENING.md § Recursion), reported at the opening
     * token. A strict parse throws; a tolerant one records it, and its caller
     * skips the block or list.
     */
    private boolean tooDeep() {
        if (depth + 1 <= limits.maxNestingDepth()) return false;
        soft(new PxfException(current.pos(), "nesting depth exceeds MaxNestingDepth=" + limits.maxNestingDepth()));
        return true;
    }

    /** A parse entry, or null where a tolerant parse skipped a token that starts none. */
    private Ast.Entry parseEntry(boolean allowMapEntry) {
        List<Ast.Comment> leading = flushComments();
        Position pp = current.pos();
        // map-key = identifier / string / integer / bool (draft -01
        // §abnf-grammar; the keyword spelling landed in protowire#284): a
        // bare true / false is a key, and like an integer it takes only
        // the ':' tail — the '=' and '{' arms below insist on an identifier.
        if (current.kind() != TokenKind.IDENT && current.kind() != TokenKind.STRING
                && current.kind() != TokenKind.INT && current.kind() != TokenKind.BOOL) {
            // An ILLEGAL token reads as the lexer's diagnostic inside this
            // expectation, as protowire-go's parseEntry words it.
            PxfException e = new PxfException(pp, "expected identifier, string, integer, or bool, got " + current.errMsg());
            if (!tolerant) throw e;
            // Skip the token and let the caller's loop try the next one; the
            // leading comments go back to wait for the next entry.
            errs.add(e);
            pendingComments.addAll(0, leading);
            advance();
            return null;
        }
        TokenKind keyKind = current.kind();
        String key = current.value();
        String keyPrec = current.prec();
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
                    // A tolerant parse records it and keeps the assignment.
                    soft(new PxfException(pp,
                            "field assignment with '=' requires an identifier or string key, got " + keyKind
                                    + " (" + Token.quote(writtenKey(key, keyPrec)) + "); use ':' for map entries"));
                }
                Position equalsPos = current.pos();
                advance();
                Ast.Value v = parseValue();
                yield new Ast.Assignment(pp, key, v, leading, takeTrailingComment(prevEnd), keyKind == TokenKind.STRING,
                        equalsPos);
            }
            case COLON -> {
                // Map entry. Only allowed inside a `{ ... }` block, never at
                // document top level. See docs/grammar.ebnf → document.
                if (!allowMapEntry) {
                    // A tolerant parse records it and keeps the map entry.
                    soft(new PxfException(pp,
                            "map entry (':' form) is only allowed inside a '{ … }' block; "
                                    + "use '=' for top-level field assignments"));
                }
                // A map key takes no precision suffix (draft -01 ABNF,
                // protowire#406): MapEntry has nowhere to keep one, so a
                // formatted document would bind another key.
                if (!keyPrec.isEmpty()) {
                    soft(new PxfException(pp, "map key " + key + ": a key cannot carry a precision suffix (p"
                            + Limits.clipLiteral(keyPrec) + ")"));
                }
                advance();
                Ast.Value v = parseValue();
                yield new Ast.MapEntry(pp, key, v, leading, takeTrailingComment(prevEnd), keyKind == TokenKind.STRING);
            }
            case LBRACE -> {
                // `{ ... }` denotes a submessage field, or a named entry of
                // a keyed repeated field; same identifier-or-string rule as
                // `=`. See docs/grammar.ebnf → field_entry.
                if (keyKind != TokenKind.IDENT && keyKind != TokenKind.STRING) {
                    soft(new PxfException(pp,
                            "submessage block requires an identifier or string key, got " + keyKind
                                    + " (" + Token.quote(writtenKey(key, keyPrec)) + ")"));
                }
                Position bracePos = current.pos();
                if (tooDeep()) {
                    // Too deep to descend: a tolerant parse skips the whole
                    // block and keeps the entry.
                    skipBalanced(TokenKind.LBRACE, TokenKind.RBRACE);
                    yield new Ast.Block(pp, key, List.of(), leading, "", keyKind == TokenKind.STRING, bracePos);
                }
                advance();
                Body body = parseBody();
                yield new Ast.Block(pp, key, body.entries(), leading, "", keyKind == TokenKind.STRING, bracePos,
                        body.trailing());
            }
            default -> {
                soft(new PxfException(current.pos(),
                        "expected '=', ':', or '{' after " + Token.quote(key) + ", got " + current.kind()));
                // A dangling key, the mid-edit shape completion fires on: an
                // assignment of a BadVal just past the key, where the value
                // belongs. The current token, most likely the next entry's
                // key, is left.
                yield new Ast.Assignment(pp, key, new Ast.BadVal(prevEnd), leading, "");
            }
        };
    }

    /**
     * An ILLEGAL token is admitted nowhere in the grammar, so the lexer's own
     * diagnostic is the useful error. Mirrors protowire-go's illegalErr().
     */
    private void rejectIllegal() {
        if (current.kind() == TokenKind.ILLEGAL) {
            throw new PxfException(current.pos(), current.value());
        }
    }

    private Ast.Value parseValue() {
        Position pp = current.pos();
        if (tolerant && currentStartsEntry()) {
            // The current token is the next entry's key: the value is
            // missing (`key =`, mid-edit). It is left, and the placeholder
            // and the error go just past the separator, where the value
            // belongs.
            errs.add(new PxfException(prevEnd, "missing value"));
            return new Ast.BadVal(prevEnd);
        }
        if (current.kind() == TokenKind.ILLEGAL) {
            if (!tolerant) rejectIllegal();
            return badValue(new PxfException(pp, current.value()));
        }
        return switch (current.kind()) {
            case STRING -> { var v = new Ast.StringVal(pp, current.value()); advance(); yield v; }
            case INT    -> { var v = new Ast.IntVal(pp, current.value(), current.prec());   advance(); yield v; }
            case FLOAT  -> { var v = new Ast.FloatVal(pp, current.value(), current.prec()); advance(); yield v; }
            case BOOL   -> { var v = new Ast.BoolVal(pp, "true".equals(current.value())); advance(); yield v; }
            case BYTES  -> {
                byte[] decoded;
                try {
                    decoded = Base64Literal.decode(current.value());
                } catch (Base64Literal.Invalid e) {
                    // Only a tolerant lexer passes a payload it cannot decode.
                    soft(new PxfException(pp, "invalid base64: " + e.getMessage()));
                    advance();
                    yield new Ast.BadVal(pp);
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
            default -> {
                PxfException e = new PxfException(pp, "expected value, got " + current.errMsg());
                if (!tolerant) throw e;
                yield badValue(e);
            }
        };
    }

    /**
     * A tolerant parse's recovery where no value stands: the error, and a
     * BadVal at the token. A closing token is left for the construct it
     * closes (a ')' closes the @dataset row around an unclosed cell); any
     * other is skipped.
     */
    private Ast.Value badValue(PxfException e) {
        errs.add(e);
        Position pp = current.pos();
        switch (current.kind()) {
            case EOF, RBRACE, RBRACKET, RPAREN -> { }
            default -> advance();
        }
        return new Ast.BadVal(pp);
    }

    private Ast.Value parseList() {
        Position pp = current.pos();
        if (tooDeep()) {
            skipBalanced(TokenKind.LBRACKET, TokenKind.RBRACKET);
            return new Ast.ListVal(pp, List.of());
        }
        advance();
        depth++;
        try {
            List<Ast.Value> elems = new ArrayList<>();
            while (current.kind() != TokenKind.RBRACKET && current.kind() != TokenKind.EOF && !tolerantRowClose()) {
                int before = current.pos().offset();
                Ast.Value elem = parseValue();
                // A tolerant parseValue that recovered without consuming
                // anything (a stray '}' that closes an enclosing block, or
                // the next entry's key) ends the list, so the construct
                // around it can resynchronize.
                if (tolerant && current.pos().offset() == before && elem instanceof Ast.BadVal) break;
                elems.add(elem);
                if (current.kind() == TokenKind.COMMA) advance();
            }
            if (current.kind() != TokenKind.RBRACKET) {
                // A tolerant parse closes the list here, with what it holds.
                soft(new PxfException(current.pos(), "expected ']', got " + current.kind()));
                return new Ast.ListVal(pp, List.copyOf(elems));
            }
            advance();
            return new Ast.ListVal(pp, List.copyOf(elems));
        } finally {
            depth--;
        }
    }

    private Ast.Value parseBlockVal() {
        Position pp = current.pos();
        if (tooDeep()) {
            skipBalanced(TokenKind.LBRACE, TokenKind.RBRACE);
            return new Ast.BlockVal(pp, List.of());
        }
        advance();
        Body body = parseBody();
        return new Ast.BlockVal(pp, body.entries(), body.trailing());
    }

    /** A block's entries, and the comments after the last of them, which no entry takes. */
    private record Body(List<Ast.Entry> entries, List<Ast.Comment> trailing) {}

    /** The entries after a '{' the caller consumed, one nesting level down. */
    private Body parseBody() {
        depth++;
        try {
            return parseBodyInner();
        } finally {
            depth--;
        }
    }

    /** Parses {@code @type = "<type-url>"}; {@code first} says it opens its block, the only place it may stand. */
    private Ast.Entry parseTypeEntry(boolean first) {
        List<Ast.Comment> leading = flushComments();
        Position pp = current.pos();
        if (!first) {
            return typeEntryFailed(leading, new PxfException(pp,
                    "@type is allowed only as the first entry of a block bound to google.protobuf.Any (draft -01 \"Any Blocks\")"));
        }
        advance(); // consume @type
        if (current.kind() != TokenKind.EQUALS) {
            return typeEntryFailed(leading, new PxfException(current.pos(), "expected '=' after @type, got " + current.errMsg()));
        }
        advance();
        if (current.kind() != TokenKind.STRING) {
            return typeEntryFailed(leading,
                    new PxfException(current.pos(), "expected a string type URL after @type =, got " + current.errMsg()));
        }
        String url = current.value();
        Position urlPos = current.pos();
        advance();
        return new Ast.TypeEntry(pp, url, leading, takeTrailingComment(prevEnd), urlPos);
    }

    /**
     * A strict parse throws a malformed type entry's error. A tolerant one
     * records it and skips what is there of the entry, {@code @type},
     * {@code =} and the string, and its leading comments wait for the next.
     */
    private Ast.Entry typeEntryFailed(List<Ast.Comment> leading, PxfException e) {
        if (!tolerant) throw e;
        errs.add(e);
        pendingComments.addAll(0, leading);
        for (TokenKind k : new TokenKind[] {TokenKind.AT_TYPE, TokenKind.EQUALS, TokenKind.STRING}) {
            if (current.kind() == k) advance();
        }
        return null;
    }

    private Body parseBodyInner() {
        List<Ast.Entry> entries = new ArrayList<>();
        while (current.kind() != TokenKind.RBRACE && current.kind() != TokenKind.EOF && !tolerantRowClose()) {
            Ast.Entry e = current.kind() == TokenKind.AT_TYPE ? parseTypeEntry(entries.isEmpty()) : parseEntry();
            if (e != null) entries.add(e);
        }
        // Taken before the '}' is consumed: advance would add the comments
        // after it, which belong to whatever follows the block (#200).
        List<Ast.Comment> trailing = flushComments();
        if (current.kind() != TokenKind.RBRACE) {
            if (!tolerant) throw new PxfException(current.pos(), "expected '}', got " + current.kind());
            // An unclosed block: closed at the end, or at the ')' of the
            // @dataset row it is a cell of, with the entries read so far.
            errs.add(new PxfException(current.pos(), current.kind() == TokenKind.RPAREN
                    ? "unclosed block: expected '}' before ')'"
                    : "unclosed block: expected '}' before end of input"));
            return new Body(List.copyOf(entries), trailing);
        }
        advance();
        return new Body(List.copyOf(entries), trailing);
    }
}
