package com.trendvidia.protowire.pxf;

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
        writeComments(sb, doc.leadingComments(), 0);
        formatEntries(sb, doc.entries(), 0);
        return sb.toString();
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
                    if (needsQuoting(m.key())) sb.append('"').append(escape(m.key())).append('"');
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
        switch (v) {
            case Ast.StringVal s   -> { sb.append('"').append(escape(s.value())).append('"'); }
            case Ast.IntVal i      -> sb.append(i.raw());
            case Ast.FloatVal f    -> sb.append(f.raw());
            case Ast.BoolVal b     -> sb.append(b.value() ? "true" : "false");
            case Ast.BytesVal by   -> sb.append("b\"").append(Base64.getEncoder().encodeToString(by.value())).append('"');
            case Ast.NullVal n     -> sb.append("null");
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

    static boolean needsQuoting(String s) {
        if (s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i == 0) {
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_')) return true;
            } else {
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) return true;
            }
        }
        return false;
    }
}
