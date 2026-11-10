// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Comment-preserving formatter that turns an {@link Ast.Document} back into PXF text. */
public final class Format {
    private Format() {}

    /**
     * Pretty-prints a document, preserving comments.
     *
     * <p>It keeps every directive, with its dataset rows, in document order
     * (trendvidia/protowire#558, #188). {@code @type} is written first: it
     * binds no anonymous {@code @proto}, so moving it changes nothing. The
     * other directives follow in source order, each after its comments: a
     * named directive's body and a {@code @proto} body as written, and a
     * dataset as its header and then one row per line, an absent cell left
     * empty. A document whose only directive is {@code @type} formats as it
     * always has. The output is byte for byte protowire-go's
     * {@code FormatDocument}.
     *
     * <p>A comment is written where it was: before the entry, directive or
     * dataset row it precedes, or before the end of its block or document
     * when none follows it (#200, protowire-go#279). A row is written on one
     * line, so a comment inside a row comes before it. Comments inside a
     * list are written after the list (protowire-go#282).
     */
    public static String formatDocument(Ast.Document doc) {
        return format(doc, false);
    }

    /**
     * {@code doc} laid out as protowire-go's {@code Marshal} writes a message:
     * as {@link #formatDocument}, except that a list or map field bound to
     * an empty value is written {@code []} or {@code {}} on its line. A
     * message carries no empty collection on the wire, so one in a document
     * read from wire bytes is an unset annotated field written so that it
     * reads back (protowire-go#263), which Marshal spells that way. And, as
     * Marshal does, it refuses a document a reader would refuse for its
     * depth: a body or list past {@code MaxNestingDepth}, counted as the
     * parser counts each '{' and '[' (HARDENING.md § Recursion,
     * #203), and a {@code pxf.BigFloat} literal whose value is past
     * MaxBigFloatExponent once rounded to its precision (#186). The lite tier
     * writes its text with this.
     *
     * @throws PxfException {@code pxf: nesting depth exceeds MaxNestingDepth=100}
     */
    public static String marshalDocument(Ast.Document doc) {
        return format(doc, true);
    }

    private static String format(Ast.Document doc, boolean compact) {
        String step = "  ";
        StringBuilder sb = new StringBuilder();
        List<Object> others = topDirectives(doc);
        if (others.isEmpty()) {
            if (!doc.typeUrl().isEmpty()) {
                sb.append("@type ").append(doc.typeUrl()).append("\n\n");
            }
            writeComments(sb, doc.leadingComments(), 0, step);
            formatEntries(sb, doc.entries(), 0, compact, step);
            writeComments(sb, doc.trailingComments(), 0, step);
            return sb.toString();
        }

        if (!doc.typeUrl().isEmpty()) {
            sb.append("@type ").append(doc.typeUrl()).append('\n');
        }
        writeComments(sb, doc.leadingComments(), 0, step);
        for (Object o : others) {
            switch (o) {
                case Ast.Directive d -> {
                    writeComments(sb, d.leadingComments(), 0, step);
                    formatNamedDirective(sb, d);
                }
                case Ast.DatasetDirective d -> {
                    writeComments(sb, d.leadingComments(), 0, step);
                    formatDataset(sb, d);
                }
                case Ast.ProtoDirective d -> {
                    writeComments(sb, d.leadingComments(), 0, step);
                    formatProto(sb, d);
                }
                default -> throw new IllegalStateException(
                        "Format: unexpected directive type " + o.getClass().getSimpleName());
            }
        }
        if (!doc.entries().isEmpty()) {
            sb.append('\n');
            formatEntries(sb, doc.entries(), 0, compact, step);
        }
        writeComments(sb, doc.trailingComments(), 0, step);
        return sb.toString();
    }

    /**
     * The document's directives other than {@code @type}, which the
     * {@link Ast.Document} keeps in three lists, merged back into source
     * order: an anonymous {@code @proto} binds the typed directive after it,
     * so the order is part of the meaning. Each element is an
     * {@link Ast.Directive}, {@link Ast.DatasetDirective} or
     * {@link Ast.ProtoDirective}.
     */
    private static List<Object> topDirectives(Ast.Document doc) {
        record At(int offset, Object d) {}
        List<At> all = new ArrayList<>();
        for (Ast.Directive d : doc.directives()) all.add(new At(d.pos().offset(), d));
        for (Ast.DatasetDirective d : doc.datasets()) all.add(new At(d.pos().offset(), d));
        for (Ast.ProtoDirective d : doc.protos()) all.add(new At(d.pos().offset(), d));
        // List.sort is stable, so directives at one offset (built in code,
        // with no position) keep the order of the lists above.
        all.sort(Comparator.comparingInt(At::offset));
        List<Object> out = new ArrayList<>(all.size());
        for (At a : all) out.add(a.d());
        return out;
    }

    /** Writes {@code @<name> prefix* [{body}]}, the body as it was written. */
    private static void formatNamedDirective(StringBuilder sb, Ast.Directive d) {
        sb.append('@').append(d.name());
        for (String p : d.prefixes()) {
            sb.append(' ').append(p);
        }
        if (d.body() != null) {
            sb.append(" {").append(new String(d.body(), StandardCharsets.UTF_8)).append('}');
        }
        sb.append('\n');
    }

    /** Writes the header, then one row per line. A null cell is an absent field, left empty; {@code null} is written. */
    private static void formatDataset(StringBuilder sb, Ast.DatasetDirective t) {
        String step = "  ";
        sb.append("@dataset");
        if (!t.type().isEmpty()) sb.append(' ').append(t.type());
        sb.append(" (").append(String.join(", ", t.columns())).append(")\n");
        for (Ast.DatasetRow row : t.rows()) {
            writeComments(sb, row.leadingComments(), 0, step);
            sb.append('(');
            for (int i = 0; i < row.cells().size(); i++) {
                if (i > 0) sb.append(", ");
                Ast.Value cell = row.cells().get(i);
                if (cell != null) formatValue(sb, cell, 0, false, step);
            }
            sb.append(")\n");
        }
    }

    /** Writes a {@code @proto} directive in the shape it was read in. */
    private static void formatProto(StringBuilder sb, Ast.ProtoDirective d) {
        sb.append("@proto");
        switch (d.shape()) {
            case ANONYMOUS -> sb.append(" {").append(new String(d.body(), StandardCharsets.UTF_8)).append('}');
            case NAMED -> sb.append(' ').append(d.typeName())
                    .append(" {").append(new String(d.body(), StandardCharsets.UTF_8)).append('}');
            case SOURCE -> sb.append(" \"\"\"").append(new String(d.body(), StandardCharsets.UTF_8)).append("\"\"\"");
            case DESCRIPTOR -> sb.append(" b\"").append(Base64.getEncoder().encodeToString(d.body())).append('"');
        }
        sb.append('\n');
    }

    private static void writeIndent(StringBuilder sb, int level, String step) {
        for (int i = 0; i < level; i++) sb.append(step);
    }

    private static void writeComments(StringBuilder sb, List<Ast.Comment> comments, int level, String step) {
        for (Ast.Comment c : comments) {
            writeIndent(sb, level, step);
            sb.append(c.text()).append('\n');
        }
    }

    /**
     * Refuses a BigFloat literal no reader takes. Only a pxf.BigFloat carries
     * a precision suffix, and the value it reads back as is bounded by
     * MaxBigFloatExponent after rounding (protowire-go#261).
     */
    private static void checkBigFloat(Ast.FloatVal f) {
        try {
            BigFloats.parse(f.raw(), f.prec(), Limits.MAX_NUMERIC_LITERAL_DIGITS, Limits.MAX_BIG_FLOAT_EXPONENT);
        } catch (IllegalArgumentException e) {
            throw new PxfException(Position.UNKNOWN, "pxf: " + e.getMessage());
        }
    }

    /** Marshal's depth check, at a body or list whose contents sit at {@code level}. */
    private static void checkDepth(int level, boolean compact) {
        if (compact && level > Limits.MAX_NESTING_DEPTH) {
            throw new PxfException(Position.UNKNOWN, "pxf: nesting depth exceeds MaxNestingDepth=" + Limits.MAX_NESTING_DEPTH);
        }
    }

    private static void formatEntries(StringBuilder sb, List<Ast.Entry> entries, int level, boolean compact, String step) {
        checkDepth(level, compact);
        for (Ast.Entry e : entries) {
            switch (e) {
                case Ast.Assignment a -> {
                    writeComments(sb, a.leadingComments(), level, step);
                    writeIndent(sb, level, step);
                    if (a.keyQuoted()) sb.append('"').append(escape(a.key())).append('"');
                    else sb.append(a.key());
                    sb.append(" = ");
                    // A list or map field's own value, never an element's
                    // or a map value's: those Marshal writes as blocks.
                    if (compact && a.value() instanceof Ast.ListVal l && l.elements().isEmpty()) {
                        checkDepth(level + 1, compact);
                        sb.append("[]");
                    } else if (compact && a.value() instanceof Ast.BlockVal bv && bv.entries().isEmpty()) {
                        checkDepth(level + 1, compact);
                        sb.append("{}");
                    }
                    else formatValue(sb, a.value(), level, compact, step);
                    if (!a.trailingComment().isEmpty()) sb.append(' ').append(a.trailingComment());
                    sb.append('\n');
                }
                case Ast.MapEntry m -> {
                    writeComments(sb, m.leadingComments(), level, step);
                    writeIndent(sb, level, step);
                    if (mapKeyQuoted(m)) sb.append('"').append(escape(m.key())).append('"');
                    else sb.append(m.key());
                    sb.append(": ");
                    formatValue(sb, m.value(), level, compact, step);
                    if (!m.trailingComment().isEmpty()) sb.append(' ').append(m.trailingComment());
                    sb.append('\n');
                }
                case Ast.TypeEntry t -> {
                    writeComments(sb, t.leadingComments(), level, step);
                    writeIndent(sb, level, step);
                    sb.append("@type = \"").append(escape(t.url())).append('"');
                    if (!t.trailingComment().isEmpty()) sb.append(' ').append(t.trailingComment());
                    sb.append('\n');
                }
                case Ast.Block b -> {
                    writeComments(sb, b.leadingComments(), level, step);
                    writeIndent(sb, level, step);
                    if (b.nameQuoted()) sb.append('"').append(escape(b.name())).append('"');
                    else sb.append(b.name());
                    sb.append(" {\n");
                    formatEntries(sb, b.entries(), level + 1, compact, step);
                    writeComments(sb, b.trailingComments(), level + 1, step);
                    writeIndent(sb, level, step);
                    sb.append("}\n");
                }
            }
        }
    }

    /**
     * {@code v} as its PXF source literal, the text {@link #formatDocument}
     * writes for a value inside a document: for tools that splice a rendered
     * value into a buffer, as protowire-go's {@code FormatValue}. A list or
     * block value spans several lines, indented two spaces, with no indent
     * on its first line and no trailing newline, so a caller splicing it
     * into an indented context re-indents the lines after the first.
     */
    public static String formatValue(Ast.Value v) {
        return appendValue(new StringBuilder(), v).toString();
    }

    /** Appends {@link #formatValue}'s text to {@code sb} and returns it, as protowire-go's {@code AppendValue}. */
    public static StringBuilder appendValue(StringBuilder sb, Ast.Value v) {
        formatValue(sb, v, 0, false, "  ");
        return sb;
    }

    static void formatValue(StringBuilder sb, Ast.Value v, int level) {
        formatValue(sb, v, level, false, "  ");
    }

    /**
     * {@code v} as {@link #formatValue} writes it, a nested level indented by
     * {@code step} rather than two spaces: the rewriter matches the
     * document's own indent width, as protowire-go's formatter does with its
     * indent (#209).
     */
    static String formatValue(Ast.Value v, String step) {
        StringBuilder sb = new StringBuilder();
        formatValue(sb, v, 0, false, step);
        return sb.toString();
    }

    /** {@code entries} as {@link #formatDocument} writes a body at level 0, each level indented by {@code step}. */
    static String formatEntries(List<Ast.Entry> entries, String step) {
        StringBuilder sb = new StringBuilder();
        formatEntries(sb, entries, 0, false, step);
        return sb.toString();
    }

    private static void formatValue(StringBuilder sb, Ast.Value v, int level, boolean compact, String step) {
        // NullVal has no payload to format — the bound variable would be
        // unused, which CodeQL flags. Java 21 standard pattern matching
        // requires a binding on every `case` label (unnamed `_` is a
        // preview feature this project doesn't enable), so we route the
        // no-binding case out before the switch.
        if (v instanceof Ast.NullVal) {
            sb.append("null");
            return;
        }
        // A tolerant parse's placeholder has no source text, so it is written
        // as nothing, as protowire-go writes it; the output may not parse.
        if (v instanceof Ast.BadVal) return;
        switch (v) {
            case Ast.StringVal s   -> sb.append('"').append(escape(s.value())).append('"');
            case Ast.IntVal i      -> sb.append(i.literal());
            case Ast.FloatVal f    -> {
                if (compact && !f.prec().isEmpty()) checkBigFloat(f);
                sb.append(f.literal());
            }
            case Ast.BoolVal b     -> sb.append(b.value() ? "true" : "false");
            case Ast.BytesVal by   -> sb.append("b\"").append(Base64.getEncoder().encodeToString(by.value())).append('"');
            case Ast.IdentVal id   -> sb.append(id.name());
            case Ast.TimestampVal t -> sb.append(t.raw());
            case Ast.DurationVal d  -> sb.append(d.raw());
            case Ast.ListVal l     -> {
                checkDepth(level + 1, compact);
                sb.append("[\n");
                for (int i = 0; i < l.elements().size(); i++) {
                    writeIndent(sb, level + 1, step);
                    formatValue(sb, l.elements().get(i), level + 1, compact, step);
                    if (i < l.elements().size() - 1) sb.append(',');
                    sb.append('\n');
                }
                writeIndent(sb, level, step);
                sb.append(']');
            }
            case Ast.BlockVal bv -> {
                sb.append("{\n");
                formatEntries(sb, bv.entries(), level + 1, compact, step);
                writeComments(sb, bv.trailingComments(), level + 1, step);
                writeIndent(sb, level, step);
                sb.append('}');
            }
            default -> throw new IllegalStateException(
                    "Format: unexpected value type " + v.getClass().getSimpleName());
        }
    }

    /** A {@code double}'s PXF text: what Go's {@code strconv.FormatFloat(v, 'g', -1, 64)} writes. */
    public static String formatDouble(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? nonFinite(v) : shortestG(Double.toString(v), t -> Double.parseDouble(t) == Math.abs(v));
    }

    /** A {@code float}'s PXF text: what Go's {@code strconv.FormatFloat(v, 'g', -1, 32)} writes. */
    public static String formatFloat(float v) {
        return Float.isNaN(v) || Float.isInfinite(v) ? nonFinite(v) : shortestG(Float.toString(v), t -> Float.parseFloat(t) == Math.abs(v));
    }

    /** Whether {@code text} is a non-finite literal: {@code inf}, {@code +inf}, {@code -inf} or {@code nan}. */
    public static boolean isNonFinite(String text) {
        return "nan".equals(text) || "inf".equals(text) || "+inf".equals(text) || "-inf".equals(text);
    }

    /**
     * The {@code double} a float literal denotes, non-finite spellings
     * included. A finite literal that rounds to infinity is refused with
     * protowire-go's message, {@code invalid double: 1e400}: draft -01
     * § Numeric Literals admits infinity only as {@code inf}, {@code +inf}
     * or {@code -inf} (#158).
     *
     * @throws NumberFormatException if {@code text} is no float literal, or overflows
     */
    public static double parseDouble(String text) {
        return switch (text) {
            case "nan" -> Double.NaN;
            case "inf", "+inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            default -> {
                double v = Double.parseDouble(text);
                if (Double.isInfinite(v)) throw new NumberFormatException("invalid double: " + text);
                yield v;
            }
        };
    }

    /**
     * The {@code float} a float literal denotes, non-finite spellings
     * included. A finite literal that rounds to infinity as a {@code float}
     * is refused as {@code invalid float: 3.4028236e38}, as in
     * {@link #parseDouble}.
     *
     * @throws NumberFormatException if {@code text} is no float literal, or overflows
     */
    public static float parseFloat(String text) {
        if (isNonFinite(text)) return (float) parseDouble(text);
        float v = Float.parseFloat(text);
        if (Float.isInfinite(v)) throw new NumberFormatException("invalid float: " + text);
        return v;
    }

    private static String nonFinite(double v) {
        return Double.isNaN(v) ? "nan" : v > 0 ? "inf" : "-inf";
    }

    /**
     * Respells Java's shortest decimal as Go's: an exponent below -4 or at
     * least 6 is written {@code 1e+06} with two digits at least, anything
     * else plainly, with no trailing {@code .0}.
     */
    private static String shortestG(String javaText, Predicate<String> readsBack) {
        boolean negative = javaText.charAt(0) == '-';
        BigDecimal d = new BigDecimal(javaText);
        if (d.signum() == 0) return negative ? "-0" : "0";
        d = d.abs().stripTrailingZeros();
        // Java keeps two digits where one would read back: 4.9E-324 is 5e-324.
        if (d.precision() == 2) {
            BigDecimal one = d.round(new MathContext(1, RoundingMode.HALF_EVEN));
            if (readsBack.test(one.toString())) d = one.stripTrailingZeros();
        }
        String digits = d.unscaledValue().toString();
        int exp = digits.length() - d.scale() - 1;
        StringBuilder sb = new StringBuilder(negative ? "-" : "");
        if (exp < -4 || exp >= 6) {
            sb.append(digits.charAt(0));
            if (digits.length() > 1) sb.append('.').append(digits, 1, digits.length());
            sb.append('e').append(exp < 0 ? '-' : '+');
            if (Math.abs(exp) < 10) sb.append('0');
            sb.append(Math.abs(exp));
        } else {
            sb.append(d.toPlainString());
        }
        return sb.toString();
    }

    /** The body of a string literal: {@code s} with its escapes, without the quotes. */
    public static String escape(String s) {
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
