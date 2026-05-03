package com.trendvidia.protowire.pxf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Single-pass tokenizer. Reads UTF-8 bytes; emits {@link Token}s.
 *
 * <p>Mirrors the Go module's lexer: triple-quoted strings, base64 bytes literals,
 * RFC 3339 timestamps, Go-style durations, comments, and dedent on triple strings.
 */
final class Lexer {
    private final byte[] input;
    int pos;
    int line = 1;
    int col = 1;

    Lexer(byte[] input) { this.input = input; }
    Lexer(String input) { this(input.getBytes(StandardCharsets.UTF_8)); }

    private byte peek() { return pos < input.length ? input[pos] : 0; }
    private byte peekAt(int off) {
        int i = pos + off;
        return i < input.length ? input[i] : 0;
    }

    private byte advance() {
        if (pos >= input.length) return 0;
        byte ch = input[pos++];
        if (ch == '\n') { line++; col = 1; } else { col++; }
        return ch;
    }

    private Position currentPos() { return new Position(line, col); }

    private void skipSpaces() {
        while (pos < input.length) {
            byte c = input[pos];
            if (c == ' ' || c == '\t' || c == '\r') advance();
            else break;
        }
    }

    Token next() {
        skipSpaces();
        if (pos >= input.length) return new Token(TokenKind.EOF, currentPos());

        Position pp = currentPos();
        byte ch = peek();

        if (ch == '\n') { advance(); return new Token(TokenKind.NEWLINE, pp); }
        if (ch == '#') return lexLineComment(pp);
        if (ch == '/' && peekAt(1) == '/') return lexLineComment(pp);
        if (ch == '/' && peekAt(1) == '*') return lexBlockComment(pp);

        if (ch == '"') {
            if (peekAt(1) == '"' && peekAt(2) == '"') return lexTripleString(pp);
            return lexString(pp);
        }
        if (ch == 'b' && peekAt(1) == '"') return lexBytes(pp);

        switch (ch) {
            case '{': advance(); return new Token(TokenKind.LBRACE, "{", pp);
            case '}': advance(); return new Token(TokenKind.RBRACE, "}", pp);
            case '[': advance(); return new Token(TokenKind.LBRACKET, "[", pp);
            case ']': advance(); return new Token(TokenKind.RBRACKET, "]", pp);
            case '=': advance(); return new Token(TokenKind.EQUALS, "=", pp);
            case ':': advance(); return new Token(TokenKind.COLON, ":", pp);
            case ',': advance(); return new Token(TokenKind.COMMA, ",", pp);
            case '@': return lexDirective(pp);
        }

        if (ch == '-' || isDigit(ch)) return lexNumber(pp);
        if (isIdentStart(ch)) return lexIdent(pp);

        advance();
        return new Token(TokenKind.ILLEGAL, String.valueOf((char) (ch & 0xff)), pp);
    }

    private Token lexLineComment(Position pp) {
        int start = pos;
        while (pos < input.length && input[pos] != '\n') advance();
        return new Token(TokenKind.COMMENT, slice(start, pos), pp);
    }

    private Token lexBlockComment(Position pp) {
        int start = pos;
        advance(); advance(); // /*
        while (pos + 1 < input.length) {
            if (input[pos] == '*' && input[pos + 1] == '/') {
                advance(); advance();
                return new Token(TokenKind.COMMENT, slice(start, pos), pp);
            }
            advance();
        }
        return new Token(TokenKind.ILLEGAL, "unterminated block comment", pp);
    }

    private Token lexString(Position pp) {
        advance(); // opening "
        // Accumulate bytes, then UTF-8-decode at the end. This mirrors the
        // Go lexer's byte-oriented accumulator so literal multi-byte UTF-8
        // round-trips correctly and \\u / \\U escapes are encoded as UTF-8
        // bytes that decode to the right Java String.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (pos < input.length) {
            int ch = advance() & 0xff;
            if (ch == '"') {
                return new Token(TokenKind.STRING, out.toString(StandardCharsets.UTF_8), pp);
            }
            if (ch != '\\') {
                out.write(ch);
                continue;
            }
            if (pos >= input.length) {
                return new Token(TokenKind.ILLEGAL, "unterminated escape sequence", pp);
            }
            int esc = advance() & 0xff;
            switch (esc) {
                case '"', '\\', '\'', '?' -> out.write(esc);
                case 'a' -> out.write(0x07);
                case 'b' -> out.write(0x08);
                case 'f' -> out.write(0x0C);
                case 'n' -> out.write('\n');
                case 'r' -> out.write('\r');
                case 't' -> out.write('\t');
                case 'v' -> out.write(0x0B);
                case 'x' -> {
                    // Exactly 2 hex digits → 1 byte.
                    if (pos + 1 >= input.length) {
                        return new Token(TokenKind.ILLEGAL,
                                "invalid \\x escape: expected 2 hex digits", pp);
                    }
                    int hi = hexVal(input[pos] & 0xff);
                    int lo = hexVal(input[pos + 1] & 0xff);
                    if (hi < 0 || lo < 0) {
                        return new Token(TokenKind.ILLEGAL,
                                "invalid \\x escape: expected 2 hex digits", pp);
                    }
                    advance(); advance();
                    out.write((hi << 4) | lo);
                }
                case '0', '1', '2', '3' -> {
                    // \\nnn — 3 octal digits, leading 0–3 keeps result <= 0xFF.
                    if (pos + 1 >= input.length) {
                        return new Token(TokenKind.ILLEGAL,
                                "invalid octal escape: expected 3 octal digits", pp);
                    }
                    int d1 = octVal(input[pos] & 0xff);
                    int d2 = octVal(input[pos + 1] & 0xff);
                    if (d1 < 0 || d2 < 0) {
                        return new Token(TokenKind.ILLEGAL,
                                "invalid octal escape: expected 3 octal digits", pp);
                    }
                    advance(); advance();
                    out.write(((esc - '0') << 6) | (d1 << 3) | d2);
                }
                case 'u' -> {
                    int r = readHexRune(4);
                    if (r < 0 || !isValidRune(r)) {
                        return new Token(TokenKind.ILLEGAL,
                                "invalid \\u escape: expected 4 hex digits forming a valid codepoint",
                                pp);
                    }
                    encodeRune(r, out);
                }
                case 'U' -> {
                    int r = readHexRune(8);
                    if (r < 0 || !isValidRune(r)) {
                        return new Token(TokenKind.ILLEGAL,
                                "invalid \\U escape: expected 8 hex digits forming a valid codepoint",
                                pp);
                    }
                    encodeRune(r, out);
                }
                default -> {
                    return new Token(TokenKind.ILLEGAL,
                            "unknown escape sequence \\" + (char) esc, pp);
                }
            }
        }
        return new Token(TokenKind.ILLEGAL, "unterminated string", pp);
    }

    private static int hexVal(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static int octVal(int c) {
        if (c >= '0' && c <= '7') return c - '0';
        return -1;
    }

    /** Reads exactly n hex digits and returns the assembled codepoint, or -1 on error. */
    private int readHexRune(int n) {
        if (pos + n > input.length) return -1;
        int r = 0;
        for (int i = 0; i < n; i++) {
            int v = hexVal(input[pos] & 0xff);
            if (v < 0) return -1;
            r = (r << 4) | v;
            advance();
        }
        return r;
    }

    /** Mirrors Go's utf8.ValidRune. */
    private static boolean isValidRune(int r) {
        return r >= 0 && r <= 0x10FFFF && (r < 0xD800 || r > 0xDFFF);
    }

    /** Writes the UTF-8 encoding of a valid Unicode scalar to out. */
    private static void encodeRune(int r, ByteArrayOutputStream out) {
        if (r <= 0x7F) {
            out.write(r);
        } else if (r <= 0x7FF) {
            out.write(0xC0 | (r >> 6));
            out.write(0x80 | (r & 0x3F));
        } else if (r <= 0xFFFF) {
            out.write(0xE0 | (r >> 12));
            out.write(0x80 | ((r >> 6) & 0x3F));
            out.write(0x80 | (r & 0x3F));
        } else {
            out.write(0xF0 | (r >> 18));
            out.write(0x80 | ((r >> 12) & 0x3F));
            out.write(0x80 | ((r >> 6) & 0x3F));
            out.write(0x80 | (r & 0x3F));
        }
    }

    private Token lexTripleString(Position pp) {
        advance(); advance(); advance(); // """
        int start = pos;
        while (pos + 2 < input.length) {
            if (input[pos] == '"' && input[pos + 1] == '"' && input[pos + 2] == '"') {
                String raw = new String(input, start, pos - start, StandardCharsets.UTF_8);
                advance(); advance(); advance();
                return new Token(TokenKind.STRING, dedent(raw), pp);
            }
            advance();
        }
        return new Token(TokenKind.ILLEGAL, "unterminated triple-quoted string", pp);
    }

    static String dedent(String s) {
        if (!s.isEmpty() && s.charAt(0) == '\n') s = s.substring(1);
        if (s.isEmpty()) return "";
        String[] lines = s.split("\n", -1);
        if (lines.length == 0) return "";
        String last = lines[lines.length - 1];
        if (last.trim().isEmpty()) {
            String indent = last;
            String[] kept = new String[lines.length - 1];
            for (int i = 0; i < kept.length; i++) {
                String line = lines[i];
                kept[i] = line.startsWith(indent) ? line.substring(indent.length()) : line;
            }
            return String.join("\n", kept);
        }
        return s;
    }

    private Token lexBytes(Position pp) {
        advance(); // b
        if (pos >= input.length || input[pos] != '"') {
            return new Token(TokenKind.ILLEGAL, "expected '\"' after b", pp);
        }
        advance(); // opening "
        int start = pos;
        while (pos < input.length) {
            byte c = input[pos];
            if (c == '"') {
                String raw = new String(input, start, pos - start, StandardCharsets.UTF_8);
                advance(); // closing "
                try {
                    Base64.getDecoder().decode(raw);
                } catch (IllegalArgumentException e) {
                    try {
                        Base64.getDecoder().decode(raw + padding(raw));
                    } catch (IllegalArgumentException e2) {
                        return new Token(TokenKind.ILLEGAL, "invalid base64 in bytes literal", pp);
                    }
                }
                return new Token(TokenKind.BYTES, raw, pp);
            }
            if (c == '\n') {
                return new Token(TokenKind.ILLEGAL, "unterminated bytes literal", pp);
            }
            advance();
        }
        return new Token(TokenKind.ILLEGAL, "unterminated bytes literal", pp);
    }

    static String padding(String s) {
        int rem = s.length() % 4;
        return rem == 0 ? "" : "====".substring(rem);
    }

    private Token lexDirective(Position pp) {
        advance(); // @
        int start = pos;
        while (pos < input.length && isIdentPart(input[pos])) advance();
        String name = slice(start, pos);
        if ("type".equals(name)) return new Token(TokenKind.AT_TYPE, "@type", pp);
        return new Token(TokenKind.ILLEGAL, "@" + name, pp);
    }

    private Token lexNumber(Position pp) {
        int start = pos;
        boolean neg = false;
        if (peek() == '-') {
            neg = true;
            advance();
            if (pos >= input.length || !isDigit(peek())) {
                return new Token(TokenKind.ILLEGAL, "-", pp);
            }
        }
        int digitStart = pos;
        while (pos < input.length && isDigit(peek())) advance();
        int digitCount = pos - digitStart;

        if (!neg && digitCount == 4 && pos < input.length && peek() == '-') {
            return lexTimestamp(pp, start);
        }
        if (pos < input.length && (peek() == '.' || peek() == 'e' || peek() == 'E')) {
            return lexFloat(pp, start);
        }
        if (pos < input.length && isDurationUnit(peek())) {
            return lexDuration(pp, start);
        }
        return new Token(TokenKind.INT, slice(start, pos), pp);
    }

    private Token lexFloat(Position pp, int start) {
        if (peek() == '.') {
            advance();
            while (pos < input.length && isDigit(peek())) advance();
        }
        if (pos < input.length && (peek() == 'e' || peek() == 'E')) {
            advance();
            if (pos < input.length && (peek() == '+' || peek() == '-')) advance();
            while (pos < input.length && isDigit(peek())) advance();
        }
        return new Token(TokenKind.FLOAT, slice(start, pos), pp);
    }

    private Token lexTimestamp(Position pp, int start) {
        while (pos < input.length) {
            byte ch = peek();
            if (ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r'
                    || ch == ',' || ch == ']' || ch == '}' || ch == '#') break;
            if (ch == '/' && (peekAt(1) == '/' || peekAt(1) == '*')) break;
            advance();
        }
        String raw = slice(start, pos);
        try {
            WellKnown.parseRfc3339(raw);
        } catch (RuntimeException e) {
            return new Token(TokenKind.ILLEGAL, "invalid timestamp: " + raw, pp);
        }
        return new Token(TokenKind.TIMESTAMP, raw, pp);
    }

    private Token lexDuration(Position pp, int start) {
        while (pos < input.length && (isDigit(peek()) || isLowerAlpha(peek()))) advance();
        String raw = slice(start, pos);
        try {
            WellKnown.parseGoDuration(raw);
        } catch (RuntimeException e) {
            return new Token(TokenKind.ILLEGAL, "invalid duration: " + raw, pp);
        }
        return new Token(TokenKind.DURATION, raw, pp);
    }

    private Token lexIdent(Position pp) {
        int start = pos;
        while (pos < input.length && isIdentPart(input[pos])) advance();
        String val = slice(start, pos);
        if ("true".equals(val) || "false".equals(val)) return new Token(TokenKind.BOOL, val, pp);
        if ("null".equals(val)) return new Token(TokenKind.NULL, val, pp);
        return new Token(TokenKind.IDENT, val, pp);
    }

    private String slice(int start, int end) {
        if (start >= end) return "";
        return new String(input, start, end - start, StandardCharsets.UTF_8);
    }

    static boolean isDigit(byte ch) { return ch >= '0' && ch <= '9'; }
    static boolean isIdentStart(byte ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch == '_';
    }
    static boolean isIdentPart(byte ch) {
        return isIdentStart(ch) || isDigit(ch) || ch == '.';
    }
    static boolean isDurationUnit(byte ch) {
        return ch == 'h' || ch == 'm' || ch == 's' || ch == 'n' || ch == 'u';
    }
    static boolean isLowerAlpha(byte ch) { return ch >= 'a' && ch <= 'z'; }
}
