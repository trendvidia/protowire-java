// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Recursive-descent parser. Produces an {@link Ast.Document}. Comments and blank lines are
 * collected into pending comments and attached to the next entry.
 */
public final class Parser {
    private final Lexer lex;
    private Token current;
    private final List<Ast.Comment> pendingComments = new ArrayList<>();

    private Parser(byte[] input) {
        this.lex = new Lexer(input);
        advance();
    }

    /** Parse a UTF-8 byte buffer of PXF text into an AST. */
    public static Ast.Document parse(byte[] input) {
        return new Parser(input).parseDocument();
    }

    public static Ast.Document parse(String input) {
        return parse(input.getBytes(StandardCharsets.UTF_8));
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
        List<Ast.TableDirective> tables = new ArrayList<>();

        // Top-of-document directives. @type, @<name>, and @table may
        // interleave in any order; @type populates typeUrl, others append
        // to their respective lists.
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
                case AT_TABLE -> tables.add(parseTableDirective());
                default -> {
                    break directives;
                }
            }
        }

        // Standalone constraint (draft §3.4.4): a document containing any
        // @table directive MUST NOT also carry @type or top-level field
        // entries — the @table header IS the document's type declaration.
        if (!tables.isEmpty()) {
            if (!typeUrl.isEmpty()) {
                throw new PxfException(tables.get(0).pos(),
                        "@table directive cannot coexist with @type; the @table header declares the document's type (draft §3.4.4)");
            }
            if (current.kind() != TokenKind.EOF) {
                throw new PxfException(current.pos(),
                        "@table directive cannot coexist with top-level field entries; the document's payload is the @table rows (draft §3.4.4)");
            }
        }

        List<Ast.Entry> entries = new ArrayList<>();
        while (current.kind() != TokenKind.EOF) {
            // Top-level: only field_entry is allowed. The document represents
            // a proto message, never a map<K,V>; map_entry (`:` form) is
            // reserved for the inside of a `{ ... }` block.
            entries.add(parseEntry(false));
        }
        return new Ast.Document(typeUrl, List.copyOf(directives), List.copyOf(tables),
                List.copyOf(entries), leading);
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
     * Parse a {@code @table <type> ( col1, col2, ... ) row*} directive.
     * AT_TABLE is current on entry (draft §3.4.4).
     */
    private Ast.TableDirective parseTableDirective() {
        List<Ast.Comment> leading = flushComments();
        Position pp = current.pos();
        advance(); // consume @table

        if (current.kind() != TokenKind.IDENT) {
            throw new PxfException(current.pos(),
                    "expected row message type after @table, got " + current.kind());
        }
        String type = current.value();
        advance();

        if (current.kind() != TokenKind.LPAREN) {
            throw new PxfException(current.pos(),
                    "expected '(' to start @table column list, got " + current.kind());
        }
        advance();

        if (current.kind() != TokenKind.IDENT) {
            throw new PxfException(current.pos(),
                    "@table column list must contain at least one field name, got " + current.kind());
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
                        "@table column \"" + colName + "\": dotted column paths are not supported in v1 (draft §3.4.4)");
            }
            columns.add(colName);
            advance();
            if (current.kind() == TokenKind.COMMA) {
                advance();
                continue;
            }
            if (current.kind() == TokenKind.RPAREN) break;
            throw new PxfException(current.pos(),
                    "expected ',' or ')' in @table column list, got " + current.kind());
        }
        advance(); // consume )

        // Zero or more rows.
        List<Ast.TableRow> rows = new ArrayList<>();
        while (current.kind() == TokenKind.LPAREN) {
            rows.add(parseTableRow(columns.size()));
        }
        return new Ast.TableDirective(pp, type, List.copyOf(columns), List.copyOf(rows), leading);
    }

    private Ast.TableRow parseTableRow(int expected) {
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
                    "expected ',' or ')' in @table row, got " + current.kind());
        }
        advance();
        if (cells.size() != expected) {
            throw new PxfException(pp,
                    "@table row has " + cells.size() + " cells, expected " + expected + " (column count)");
        }
        // Row cells legitimately contain null for empty cells. List.copyOf
        // rejects nulls, so wrap an ArrayList copy via Collections instead.
        return new Ast.TableRow(pp, java.util.Collections.unmodifiableList(new ArrayList<>(cells)));
    }

    /**
     * Consume one cell of a @table row. Returns {@code null} for an empty
     * cell (no value between commas, or at row start/end). Rejects list
     * and block values per v1 cell-grammar (draft §3.4.4).
     */
    private Ast.Value parseRowCell() {
        switch (current.kind()) {
            case COMMA, RPAREN -> {
                return null;
            }
            case LBRACKET -> throw new PxfException(current.pos(),
                    "@table cells cannot contain list values in v1 (draft §3.4.4)");
            case LBRACE -> throw new PxfException(current.pos(),
                    "@table cells cannot contain block values in v1 (draft §3.4.4)");
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
        if (current.kind() != TokenKind.IDENT && current.kind() != TokenKind.STRING && current.kind() != TokenKind.INT) {
            throw new PxfException(pp, "expected identifier, string, or integer, got " + current.kind() + " (\"" + current.value() + "\")");
        }
        TokenKind keyKind = current.kind();
        String key = current.value();
        advance();

        return switch (current.kind()) {
            case EQUALS -> {
                // `=` denotes a field assignment on a proto message; the key
                // must be an identifier (= proto field name). Map-style keys
                // (string/integer) are only valid with `:`. See
                // docs/grammar.ebnf → field_entry.
                if (keyKind != TokenKind.IDENT) {
                    throw new PxfException(pp,
                            "field assignment with '=' requires an identifier key, got " + keyKind
                                    + " (\"" + key + "\"); use ':' for map entries");
                }
                advance();
                Ast.Value v = parseValue();
                yield new Ast.Assignment(pp, key, v, leading, "");
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
                yield new Ast.MapEntry(pp, key, v, leading, "");
            }
            case LBRACE -> {
                // `{ ... }` denotes a submessage field; same identifier-only
                // rule as `=` applies. See docs/grammar.ebnf → field_entry.
                if (keyKind != TokenKind.IDENT) {
                    throw new PxfException(pp,
                            "submessage block requires an identifier key, got " + keyKind
                                    + " (\"" + key + "\")");
                }
                advance();
                List<Ast.Entry> body = parseBody();
                yield new Ast.Block(pp, key, body, leading, "");
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
                var t = WellKnown.parseRfc3339(current.value());
                var v = new Ast.TimestampVal(pp, t, current.value());
                advance(); yield v;
            }
            case DURATION -> {
                var d = WellKnown.parseGoDuration(current.value());
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

    private Ast.Value parseList() {
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
