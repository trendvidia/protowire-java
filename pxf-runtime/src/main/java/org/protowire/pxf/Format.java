// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** Comment-preserving formatter that turns an {@link Ast.Document} back into PXF text. */
public final class Format {
    private Format() {}

    public static String formatDocument(Ast.Document doc) {
        StringBuilder sb = new StringBuilder();
        if (!doc.typeUrl().isEmpty()) {
            sb.append("@type ").append(doc.typeUrl()).append("\n\n");
        }
        for (Ast.Directive d : doc.directives()) {
            formatDirective(sb, d);
            sb.append('\n');
        }
        for (Ast.DatasetDirective t : doc.datasets()) {
            formatTableDirective(sb, t);
            sb.append('\n');
        }
        writeComments(sb, doc.leadingComments(), 0);
        formatEntries(sb, doc.entries(), 0);
        return sb.toString();
    }

    private static void formatDirective(StringBuilder sb, Ast.Directive d) {
        sb.append('@').append(d.name());
        for (String p : d.prefixes()) {
            sb.append(' ').append(p);
        }
        if (d.body() != null) {
            sb.append(" {").append(new String(d.body(), StandardCharsets.UTF_8)).append('}');
        }
        sb.append('\n');
    }

    private static void formatTableDirective(StringBuilder sb, Ast.DatasetDirective t) {
        sb.append("@dataset ").append(t.type()).append(" (");
        for (int i = 0; i < t.columns().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(t.columns().get(i));
        }
        sb.append(")\n");
        for (Ast.DatasetRow row : t.rows()) {
            sb.append('(');
            for (int i = 0; i < row.cells().size(); i++) {
                if (i > 0) sb.append(", ");
                Ast.Value cell = row.cells().get(i);
                if (cell != null) formatValue(sb, cell, 0);
                // null cell ⇒ empty between commas, leave it blank.
            }
            sb.append(")\n");
        }
    }

    private static void writeIndent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) sb.append("  ");
    }

    private static void writeComments(StringBuilder sb, List<Ast.Comment> comments, int level) {
        for (Ast.Comment c : comments) {
            writeIndent(sb, level);
            sb.append(c.text()).append('\n');
        }
    }

    private static void formatEntries(StringBuilder sb, List<Ast.Entry> entries, int level) {
        for (Ast.Entry e : entries) {
            switch (e) {
                case Ast.Assignment a -> {
                    writeComments(sb, a.leadingComments(), level);
                    writeIndent(sb, level);
                    sb.append(a.key()).append(" = ");
                    formatValue(sb, a.value(), level);
                    if (!a.trailingComment().isEmpty()) sb.append(' ').append(a.trailingComment());
                    sb.append('\n');
                }
                case Ast.MapEntry m -> {
                    writeComments(sb, m.leadingComments(), level);
                    writeIndent(sb, level);
                    if (mapKeyQuoted(m)) sb.append('"').append(escape(m.key())).append('"');
                    else sb.append(m.key());
                    sb.append(": ");
                    formatValue(sb, m.value(), level);
                    if (!m.trailingComment().isEmpty()) sb.append(' ').append(m.trailingComment());
                    sb.append('\n');
                }
                case Ast.Block b -> {
                    writeComments(sb, b.leadingComments(), level);
                    writeIndent(sb, level);
                    sb.append(b.name()).append(" {\n");
                    formatEntries(sb, b.entries(), level + 1);
                    writeIndent(sb, level);
                    sb.append("}\n");
                }
            }
        }
    }

    private static void formatValue(StringBuilder sb, Ast.Value v, int level) {
        // NullVal has no payload to format — the bound variable would be
        // unused, which CodeQL flags. Java 21 standard pattern matching
        // requires a binding on every `case` label (unnamed `_` is a
        // preview feature this project doesn't enable), so we route the
        // no-binding case out before the switch.
        if (v instanceof Ast.NullVal) {
            sb.append("null");
            return;
        }
        switch (v) {
            case Ast.StringVal s   -> sb.append('"').append(escape(s.value())).append('"');
            case Ast.IntVal i      -> sb.append(i.raw());
            case Ast.FloatVal f    -> sb.append(f.raw());
            case Ast.BoolVal b     -> sb.append(b.value() ? "true" : "false");
            case Ast.BytesVal by   -> sb.append("b\"").append(Base64.getEncoder().encodeToString(by.value())).append('"');
            case Ast.IdentVal id   -> sb.append(id.name());
            case Ast.TimestampVal t -> sb.append(t.raw());
            case Ast.DurationVal d  -> sb.append(d.raw());
            case Ast.ListVal l     -> {
                sb.append("[\n");
                for (int i = 0; i < l.elements().size(); i++) {
                    writeIndent(sb, level + 1);
                    formatValue(sb, l.elements().get(i), level + 1);
                    if (i < l.elements().size() - 1) sb.append(',');
                    sb.append('\n');
                }
                writeIndent(sb, level);
                sb.append(']');
            }
            case Ast.BlockVal bv -> {
                sb.append("{\n");
                formatEntries(sb, bv.entries(), level + 1);
                writeIndent(sb, level);
                sb.append('}');
            }
            default -> throw new IllegalStateException(
                    "Format: unexpected value type " + v.getClass().getSimpleName());
        }
    }

    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\x%02x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    /**
     * Whether a marshaller writes a string key quoted: iff it is not
     * identifier-safe ({@link #identSafeEntryName}) — so {@code "true"},
     * {@code "false"}, {@code "null"} and integer-shaped keys are always
     * quoted, since bare they would denote a bool or an integer key
     * (draft -01 § Entries and Keys, "Canonical spelling of map keys";
     * protowire#306, #82).
     */
    public static boolean needsQuoting(String s) { return !identSafeEntryName(s); }

    /**
     * The identifier-safe test shared by string map keys and keyed entry
     * names: the {@code identifier} production of draft -01
     * ({@code ident-start = ALPHA / "_"}, {@code ident-part = ALPHA / DIGIT
     * / "_" / "."}, dots included — protowire#313, #83) minus the value
     * keywords {@code null} / {@code true} / {@code false}. Such a key is
     * written bare and reads back as the same string; anything else is
     * quoted — so {@code a.b} is bare and {@code ".e"}, {@code "1.5"} and
     * {@code "404"}, which fail ident-start, stay quoted.
     */
    public static boolean identSafeEntryName(String s) {
        if (s.isEmpty() || "true".equals(s) || "false".equals(s) || "null".equals(s)) return false;
        if (!isIdentStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!isIdentPart(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9') || c == '.';
    }

    /**
     * The spelling of a map key on the way out. A key the document wrote
     * quoted keeps its quotes unless it is identifier-safe; a key the
     * document wrote bare stays bare, because the bare spellings
     * {@code true}, {@code 0} and {@code 123} denote a bool or an integer
     * key and quoting them would change what they denote. A
     * {@link Ast.MapEntry} built in code carries no document spelling: it
     * is written bare when its key lexes as one bare map-key token and
     * quoted otherwise, so {@code ""} or {@code "my key"} never produce a
     * document that does not parse. Mirrors protowire-go's
     * {@code mapKeyQuoted} (protowire#306, protowire-go#123).
     */
    static boolean mapKeyQuoted(Ast.MapEntry e) {
        if (e.keyQuoted()) return needsQuoting(e.key());
        return !lexesAsBareMapKey(e.key());
    }

    /**
     * Whether {@code s} reads back as exactly one bare map-key token — an
     * identifier, an integer or a bool ({@code map-key = identifier /
     * string / integer / bool}) — spelled as {@code s}. {@code null} is not
     * one.
     */
    private static boolean lexesAsBareMapKey(String s) {
        Lexer l = new Lexer(s);
        Token t = l.next();
        return switch (t.kind()) {
            case IDENT, INT, BOOL -> s.equals(t.value()) && l.next().kind() == TokenKind.EOF;
            default -> false;
        };
    }
}
