// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

/** A lexical token. */
public record Token(TokenKind kind, String value, Position pos, String prec) {
    public Token(TokenKind kind, Position pos) { this(kind, "", pos, ""); }
    public Token(TokenKind kind, String value, Position pos) { this(kind, value, pos, ""); }

    /**
     * This token inside an error that keeps its own expectation, such as
     * {@code expected map key, got <this>}: the lexer's diagnostic for an
     * {@link TokenKind#ILLEGAL} token, else its kind and quoted value, as
     * {@code , (",")}. Mirrors protowire-go's {@code tokenErrMsg}, so both
     * say the same.
     */
    public String errMsg() {
        return kind == TokenKind.ILLEGAL ? value : kind + " (" + quote(value) + ")";
    }

    /** {@code s} double-quoted with Go's escapes, as {@code %q} writes a string. */
    public static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        s.codePoints().forEach(cp -> escape(b, cp, '"'));
        return b.append('"').toString();
    }

    /** {@code cp} single-quoted with Go's escapes, as {@code %q} writes a rune. */
    public static String quoteRune(int cp) {
        StringBuilder b = new StringBuilder(8).append('\'');
        escape(b, cp, '\'');
        return b.append('\'').toString();
    }

    private static void escape(StringBuilder b, int cp, char quote) {
        if (cp == quote || cp == '\\') {
            b.append('\\').appendCodePoint(cp);
            return;
        }
        if (printable(cp)) {
            b.appendCodePoint(cp);
            return;
        }
        switch (cp) {
            case 0x07 -> b.append("\\a");
            case '\b' -> b.append("\\b");
            case '\f' -> b.append("\\f");
            case '\n' -> b.append("\\n");
            case '\r' -> b.append("\\r");
            case '\t' -> b.append("\\t");
            case 0x0b -> b.append("\\v");
            default -> {
                if (cp < ' ' || cp == 0x7f) b.append(String.format("\\x%02x", cp));
                else if (cp < 0x10000) b.append(String.format("\\u%04x", cp));
                else b.append(String.format("\\U%08x", cp));
            }
        }
    }

    /** Go's {@code unicode.IsPrint}: letters, marks, numbers, punctuation, symbols and the ASCII space. */
    private static boolean printable(int cp) {
        if (cp == ' ') return true;
        return switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
                 Character.UNASSIGNED, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR,
                 Character.PARAGRAPH_SEPARATOR -> false;
            default -> true;
        };
    }
}
