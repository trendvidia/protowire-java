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
    /**
     * Maximum nesting depth permitted for {@code {...}} blocks and {@code [...]}
     * lists. Mirrors HARDENING.md §Recursion ({@code MaxNestingDepth = 100}) —
     * bounds native call-stack growth on adversarial input.
     */
    static final int MAX_NESTING_DEPTH = 100;

    private final Lexer lex;
    private Token current;
    private int depth;
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
        if (current.kind() == TokenKind.AT_TYPE) {
            advance();
            if (current.kind() != TokenKind.IDENT) {
                throw new PxfException(current.pos(), "expected type name after @type, got " + current.kind());
            }
            typeUrl = current.value();
            advance();
        }
        List<Ast.Entry> entries = new ArrayList<>();
        while (current.kind() != TokenKind.EOF) {
            entries.add(parseEntry());
        }
        return new Ast.Document(typeUrl, List.copyOf(entries), leading);
    }

    private Ast.Entry parseEntry() {
        List<Ast.Comment> leading = flushComments();
        Position pp = current.pos();
        if (current.kind() != TokenKind.IDENT && current.kind() != TokenKind.STRING && current.kind() != TokenKind.INT) {
            throw new PxfException(pp, "expected identifier, string, or integer, got " + current.kind() + " (\"" + current.value() + "\")");
        }
        String key = current.value();
        advance();

        return switch (current.kind()) {
            case EQUALS -> {
                advance();
                Ast.Value v = parseValue();
                yield new Ast.Assignment(pp, key, v, leading, "");
            }
            case COLON -> {
                advance();
                Ast.Value v = parseValue();
                yield new Ast.MapEntry(pp, key, v, leading, "");
            }
            case LBRACE -> {
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

    private Ast.Value parseList() {
        Position pp = current.pos();
        enterNesting(pp);
        try {
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
        } finally {
            depth--;
        }
    }

    private Ast.Value parseBlockVal() {
        Position pp = current.pos();
        advance();
        List<Ast.Entry> body = parseBody();
        return new Ast.BlockVal(pp, body);
    }

    private List<Ast.Entry> parseBody() {
        enterNesting(current.pos());
        try {
            List<Ast.Entry> entries = new ArrayList<>();
            while (current.kind() != TokenKind.RBRACE && current.kind() != TokenKind.EOF) {
                entries.add(parseEntry());
            }
            if (current.kind() != TokenKind.RBRACE) {
                throw new PxfException(current.pos(), "expected '}', got " + current.kind());
            }
            advance();
            return List.copyOf(entries);
        } finally {
            depth--;
        }
    }

    private void enterNesting(Position pp) {
        if (++depth > MAX_NESTING_DEPTH) {
            throw new PxfException(pp,
                    "max nesting depth (" + MAX_NESTING_DEPTH + ") exceeded");
        }
    }
}
