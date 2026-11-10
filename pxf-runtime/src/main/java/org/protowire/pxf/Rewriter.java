// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Targeted, format-preserving edits to a PXF document, as protowire-go's
 * {@code Rewriter} (#209). Unlike {@link Format#formatDocument}, which
 * normalizes the whole document, a Rewriter splices replacement bytes into
 * the original source, so everything outside the edited spans (comments,
 * blank lines, key order, indentation, number and string spellings)
 * round-trips byte for byte. It is the write path for tools that edit
 * documents a person also edits by hand.
 *
 * <pre>{@code
 * Rewriter r = new Rewriter(src);
 * r.set("server.port", new Ast.IntVal(Position.UNKNOWN, "9090"));
 * r.remove("server.debug");
 * byte[] out = r.bytes();
 * }</pre>
 *
 * <p>Fields are addressed by dotted path: each segment names an
 * {@link Ast.Assignment} key, {@link Ast.Block} name or {@link Ast.MapEntry}
 * key in the enclosing scope, and the first match wins. A key that holds a
 * dot cannot be addressed. Edits are computed against the document as first
 * parsed: staging one does not make new fields addressable, and two
 * {@link #set} calls that both create the same missing block create it twice
 * (stage one with a {@link Ast.BlockVal} instead).
 *
 * <p>Keyed collections (draft -01 §3.13) have their own editors, which take
 * the element key as an opaque atom: {@link #setKeyed}, {@link #removeKeyed},
 * {@link #insertKeyedElement}, {@link #removeKeyedElement},
 * {@link #renameKeyedElement} and {@link #moveKeyedElement}. They search
 * every binding of a field bound more than once, and the anonymous list form
 * ({@code f = [ { k = "x" } ]}) once its key field is named with
 * {@link #keyedByField}; the keyed-block form needs no declaration.
 *
 * <p>An edit the document cannot take throws {@link IllegalArgumentException}
 * with protowire-go's text. A Rewriter is not safe for concurrent use.
 */
public final class Rewriter {

    /** Replaces {@code src[start, end)} with {@code text}: a pure insertion when start == end, a deletion when text is empty. */
    private record SpanEdit(String path, int start, int end, byte[] text) {}

    private final byte[] src;
    private final Ast.Document doc;
    private final List<SpanEdit> edits = new ArrayList<>();
    /** Gives each appended entry its own edit identity, so appends into one block accumulate. */
    private int insertSeq;
    /** The (pxf.key) field declared for the keyed collection at a dotted field path, by {@link #keyedByField}. */
    private final Map<String, String> keyFields = new HashMap<>();

    /**
     * A Rewriter over {@code src}, parsed strictly with {@link Parser#parse}.
     * The source must be valid PXF: a broken document has no stable spans to
     * splice into.
     *
     * @throws PxfException the parse's error
     */
    public Rewriter(byte[] src) {
        this.src = src.clone();
        this.doc = Parser.parse(this.src);
    }

    /** The parsed document paths are resolved against. It is the source as given, not the staged edits. */
    public Ast.Document document() {
        return doc;
    }

    private static IllegalArgumentException fail(String msg) {
        return new IllegalArgumentException(msg);
    }

    // -- Set / Remove / ReplaceValue / SetSpan / AppendEntry --------------------

    /**
     * Stages an upsert of the field at {@code path}. When the path resolves to
     * an assignment or map entry, only its value's span is replaced: the key,
     * the separator, the alignment and a trailing comment stay. When it (or a
     * suffix of it) does not exist, the missing chain is inserted at the end
     * of the deepest existing block, indented like its siblings. A path that
     * resolves to a block is refused: replacing a block would discard the
     * comments in it. An inserted leaf takes the ':' form when a sibling is a
     * map entry, the '=' form otherwise. Setting the same path again replaces
     * the staged edit.
     */
    public void set(String path, Ast.Value v) {
        if (v == null) throw fail("pxf: Set " + path + ": nil value");
        if (containsBadVal(v)) throw fail("pxf: Set " + path + ": cannot write a BadVal placeholder");
        Target t = resolve(path);
        setResolved("Set " + path, path, t, v);
    }

    private void setResolved(String op, String pathKey, Target t, Ast.Value v) {
        if (t.entry() != null) {
            Ast.Value old = entryValue(t.entry());
            if (old == null) {
                throw fail("pxf: " + op + ": path addresses a block; set its fields individually, or Remove it and Set a BlockVal");
            }
            int start = old.pos().offset();
            int end = old.end().offset();
            String indent = lineIndentAt(t.entry().pos().offset());
            stage(new SpanEdit(pathKey, start, end, renderValue(v, indent, stepFor(t.containerEntries()))));
            return;
        }
        stageInsert(op, pathKey, t, v);
    }

    /**
     * Stages the removal of the entry at {@code path}. When the entry sits
     * alone on its line(s), the whole lines go, a trailing comment on its last
     * line included; comment lines above it stay, since they may describe the
     * group around it. When it shares a line, only its span goes.
     */
    public void remove(String path) {
        Target t = resolve(path);
        if (t.entry() == null) throw fail("pxf: Remove " + path + ": no such entry");
        int[] span = entryRemovalSpan(t.entry());
        stage(new SpanEdit(path, span[0], span[1], new byte[0]));
    }

    /** The span removing {@code e} deletes, and 1 at [2] when that is its whole lines. */
    private int[] entryRemovalSpan(Ast.Entry e) {
        int start = e.pos().offset();
        int end = e.end().offset();
        int ls = lineStartOffset(src, start);
        if (isBlank(src, ls, start)) {
            // The entry starts its line; try to take it through the line's end.
            int le = end;
            while (le < src.length && (src[le] == ' ' || src[le] == '\t')) le++;
            if (le < src.length && (src[le] == '#' || (src[le] == '/' && le + 1 < src.length && src[le + 1] == '/'))) {
                while (le < src.length && src[le] != '\n') le++;
            } else if (le + 1 < src.length && src[le] == '/' && src[le + 1] == '*') {
                // A trailing block comment goes only when it closes on the
                // line; a multi-line one is not the entry's own.
                int close = indexOf(src, le, "*/");
                if (close >= 0) {
                    int through = close + 2;
                    if (indexOf(src, le, through, (byte) '\n') < 0) {
                        le = through;
                        while (le < src.length && (src[le] == ' ' || src[le] == '\t')) le++;
                    }
                }
            }
            if (le >= src.length || src[le] == '\n') {
                if (le < src.length) le++; // the newline
                return new int[] {ls, le, 1};
            }
        }
        // It shares its line(s): its span, and the spaces after it.
        while (end < src.length && (src[end] == ' ' || src[end] == '\t')) end++;
        return new int[] {start, end, 0};
    }

    /**
     * Stages the replacement of an existing value's span with {@code with}.
     * Where {@link #set} addresses a value by its first-match path, this takes
     * the node itself, from {@link #document()}, so it can change one of
     * several siblings that share a key. {@code old} must be a value parsed
     * from this source: a value built in code, or a BadVal, has no span.
     */
    public void replaceValue(Ast.Value old, Ast.Value with) {
        if (old == null) throw fail("pxf: ReplaceValue: nil old value");
        if (with == null) throw fail("pxf: ReplaceValue: nil replacement value");
        if (containsBadVal(with)) throw fail("pxf: ReplaceValue: cannot write a BadVal placeholder");
        int start = old.pos().offset();
        int end = old.end().offset();
        if (start >= end) {
            throw fail("pxf: ReplaceValue: old value has no source span (not a parsed value, or a BadVal)");
        }
        setSpan(start, end, renderValue(with, lineLeadingIndent(start), indentStep()));
    }

    /**
     * Stages the replacement of {@code src[start, end)} with {@code text}, the
     * raw edit beneath the others: start == end inserts, an empty text
     * deletes. Staging the same span again replaces it.
     */
    public void setSpan(int start, int end, byte[] text) {
        if (start < 0 || end < start || end > src.length) {
            throw fail("pxf: SetSpan: span [" + start + ":" + end + ") out of range for source of length " + src.length);
        }
        stage(new SpanEdit(spanPath(start, end), start, end, text == null ? new byte[0] : text.clone()));
    }

    /**
     * Stages {@code e} as the last entry of {@code block}, a node from
     * {@link #document()}, laid out like its siblings: on a new line before
     * the closing brace of a multi-line block, inline before the brace of an
     * inline one. Appends into one block accumulate in call order.
     */
    public void appendEntry(Ast.Block block, Ast.Entry e) {
        if (block == null) throw fail("pxf: AppendEntry: nil block");
        if (e == null) throw fail("pxf: AppendEntry: nil entry");
        if (entryContainsBadVal(e)) throw fail("pxf: AppendEntry: cannot write a BadVal placeholder");
        int closeOff = block.end().offset() - 1;
        int scopeStart = block.pos().offset();
        if (closeOff < scopeStart || closeOff >= src.length || src[closeOff] != '}') {
            throw fail("pxf: AppendEntry: block " + Token.quote(block.name()) + " has no source span (not a parsed block)");
        }
        Insert ins = renderAppendEntry(scopeStart, closeOff, block.entries(), e);
        stageInsertEdit(ins.at(), ins.text());
    }

    private record Insert(int at, byte[] text) {}

    private Insert renderAppendEntry(int scopeStart, int closeOff, List<Ast.Entry> siblings, Ast.Entry e) {
        if (indexOf(src, scopeStart, closeOff, (byte) '\n') < 0) {
            // An inline block `{ ... }` (or `{}`): ` <entry> ` before the '}'.
            Buf sb = new Buf();
            if (closeOff > 0 && src[closeOff - 1] != ' ' && src[closeOff - 1] != '\t') sb.write(' ');
            writeEntryInline(sb, e);
            sb.write(' ');
            return new Insert(closeOff, sb.bytes());
        }
        // A multi-line block: a full line above the '}', indented like the
        // siblings, a nested body stepping in by the document's own width.
        String step = stepFor(siblings);
        String indent = childIndent(siblings, scopeStart);
        if (siblings.isEmpty()) indent = lineLeadingIndent(scopeStart) + step;
        int insertAt = closeOff;
        String prefix = "";
        int ls = lineStartOffset(src, closeOff);
        if (isBlank(src, ls, closeOff)) {
            insertAt = ls;
        } else {
            prefix = "\n"; // the '}' shares a line with the last entry
        }
        Buf sb = new Buf();
        sb.write(prefix);
        sb.write(renderEntryLines(e, indent, step));
        sb.write('\n');
        return new Insert(insertAt, sb.bytes());
    }

    private void stageInsertEdit(int off, byte[] text) {
        insertSeq++;
        stage(new SpanEdit("\0insert#" + insertSeq, off, off, text));
    }

    private static String spanPath(int start, int end) {
        return "\0span:" + start + ":" + end;
    }

    /**
     * The document with the staged edits applied. Edits must not overlap; a
     * pair that does is refused. The result is parsed again before it is
     * returned, so a rewrite that makes invalid PXF is an error, not a
     * broken document. The source is never changed.
     *
     * @throws IllegalStateException on overlapping edits or an invalid result
     */
    public byte[] bytes() {
        if (edits.isEmpty()) return src.clone();
        List<SpanEdit> es = new ArrayList<>(edits);
        es.sort(Comparator.comparingInt(SpanEdit::start)); // stable
        for (int i = 1; i < es.size(); i++) {
            if (es.get(i - 1).end() > es.get(i).start()) {
                throw new IllegalStateException("pxf: conflicting edits: " + es.get(i - 1).path() + " overlaps " + es.get(i).path());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(src.length + 64);
        int last = 0;
        for (SpanEdit e : es) {
            out.write(src, last, e.start() - last);
            out.writeBytes(e.text());
            last = e.end();
        }
        out.write(src, last, src.length - last);
        byte[] result = out.toByteArray();
        try {
            Parser.parse(result);
        } catch (PxfException e) {
            throw new IllegalStateException("pxf: rewrite produced an invalid document: " + e.getMessage(), e);
        }
        return result;
    }

    /** Records an edit, replacing a staged one for the same path. */
    private void stage(SpanEdit e) {
        for (int i = 0; i < edits.size(); i++) {
            if (edits.get(i).path().equals(e.path())) {
                edits.set(i, e);
                return;
            }
        }
        edits.add(e);
    }

    // -- path resolution -----------------------------------------------------

    /**
     * A resolved path: the entry, or the deepest existing scope and the
     * missing trailing segments. {@code container} is the innermost resolved
     * ancestor (a block, or an entry holding a block value), null at the top
     * level.
     */
    private record Target(Ast.Entry entry, Ast.Entry container, List<Ast.Entry> containerEntries, List<String> missing) {}

    private Target resolve(String path) {
        return resolveIn(doc.entries(), null, path);
    }

    private Target resolveIn(List<Ast.Entry> entries, Ast.Entry container, String path) {
        String[] segs = path.split("\\.", -1);
        for (String seg : segs) {
            if (seg.isEmpty()) throw fail("pxf: invalid path " + Token.quote(path) + ": empty segment");
        }
        for (int i = 0; i < segs.length; i++) {
            Ast.Entry e = findEntry(entries, segs[i]);
            if (e == null) {
                return new Target(null, container, entries, List.of(Arrays.copyOfRange(segs, i, segs.length)));
            }
            if (i == segs.length - 1) return new Target(e, container, entries, List.of());
            if (e instanceof Ast.Block b) {
                entries = b.entries();
            } else if (entryValue(e) instanceof Ast.BlockVal bv) {
                entries = bv.entries();
            } else {
                throw fail("pxf: path " + Token.quote(path) + ": segment " + Token.quote(segs[i]) + " is a scalar field, not a block");
            }
            container = e;
        }
        throw fail("pxf: invalid path " + Token.quote(path)); // unreachable: segs is never empty
    }

    private static String entryKey(Ast.Entry e) {
        return switch (e) {
            case Ast.Assignment a -> a.key();
            case Ast.MapEntry m -> m.key();
            case Ast.Block b -> b.name();
            default -> "";
        };
    }

    /** The value of a leaf entry, or null for a block. */
    private static Ast.Value entryValue(Ast.Entry e) {
        return switch (e) {
            case Ast.Assignment a -> a.value();
            case Ast.MapEntry m -> m.value();
            default -> null;
        };
    }

    private static Ast.Entry findEntry(List<Ast.Entry> entries, String key) {
        for (Ast.Entry e : entries) {
            if (entryKey(e).equals(key)) return e;
        }
        return null;
    }

    private static boolean containsBadVal(Ast.Value v) {
        if (v instanceof Ast.BadVal) return true;
        if (v instanceof Ast.ListVal l) {
            for (Ast.Value e : l.elements()) if (containsBadVal(e)) return true;
        } else if (v instanceof Ast.BlockVal b) {
            for (Ast.Entry e : b.entries()) if (entryContainsBadVal(e)) return true;
        }
        return false;
    }

    private static boolean entryContainsBadVal(Ast.Entry e) {
        return switch (e) {
            case Ast.Assignment a -> containsBadVal(a.value());
            case Ast.MapEntry m -> containsBadVal(m.value());
            case Ast.Block b -> b.entries().stream().anyMatch(Rewriter::entryContainsBadVal);
            default -> false;
        };
    }

    private static boolean needsQuoting(String s) {
        return !Format.identSafeEntryName(s);
    }

    /** Stages the missing tail of a path, at the end of the deepest existing scope. */
    private void stageInsert(String op, String pathKey, Target t, Ast.Value v) {
        List<String> missing = t.missing();
        // The block-creating segments must be bare identifiers; the leaf may
        // be quoted, in the map (':') form only.
        for (String seg : missing.subList(0, missing.size() - 1)) {
            if (needsQuoting(seg)) throw fail("pxf: " + op + ": segment " + Token.quote(seg) + " is not a valid block name");
        }
        String leaf = missing.get(missing.size() - 1);
        boolean mapForm = hasMapEntry(t.containerEntries());
        if (needsQuoting(leaf) && !mapForm) throw fail("pxf: " + op + ": key " + Token.quote(leaf) + " needs a map (':') context");
        String step = stepFor(t.containerEntries());

        int closeOff;   // the closing '}' (top level: the end of the source)
        int scopeStart; // where the block's entry starts, for the indent fallback
        Ast.Entry c = t.container();
        if (c == null) {
            closeOff = src.length;
            scopeStart = -1;
        } else if (c instanceof Ast.Block b) {
            closeOff = b.end().offset() - 1;
            scopeStart = b.pos().offset();
        } else {
            closeOff = entryValue(c).end().offset() - 1;
            scopeStart = c.pos().offset();
        }
        if (scopeStart >= 0 && (closeOff < 0 || closeOff >= src.length || src[closeOff] != '}')) {
            throw fail("pxf: " + op + ": internal error: container span does not end at '}'");
        }
        if (scopeStart >= 0 && indexOf(src, scopeStart, closeOff, (byte) '\n') < 0) {
            // An inline block: ` key = value ` just before the '}'.
            Buf sb = new Buf();
            if (closeOff > 0 && src[closeOff - 1] != ' ' && src[closeOff - 1] != '\t') sb.write(' ');
            sb.write(renderChain(missing, "", step, mapForm, v, true));
            sb.write(' ');
            stage(new SpanEdit(pathKey, closeOff, closeOff, sb.bytes()));
            return;
        }
        // A multi-line scope: full lines above the '}' (top level: at the
        // end), indented like the siblings.
        String indent = siblingIndent(t);
        int insertAt = closeOff;
        String prefix = "";
        if (scopeStart >= 0) {
            int ls = lineStartOffset(src, closeOff);
            if (isBlank(src, ls, closeOff)) insertAt = ls;
            else prefix = "\n";
        } else if (src.length > 0 && src[src.length - 1] != '\n') {
            prefix = "\n";
        }
        Buf sb = new Buf();
        sb.write(prefix);
        sb.write(renderChain(missing, indent, step, mapForm, v, false));
        sb.write('\n');
        stage(new SpanEdit(pathKey, insertAt, insertAt, sb.bytes()));
    }

    /** The missing tail: nested block opens, then {@code leaf = value} (or {@code leaf: value}). */
    private byte[] renderChain(List<String> missing, String baseIndent, String step, boolean mapForm, Ast.Value v, boolean inline) {
        Buf sb = new Buf();
        // Only the leaf's own container decides '=' or ':'; a block made here
        // is a message, so its leaf takes '='.
        boolean leafMap = mapForm && missing.size() == 1;
        for (int i = 0; i < missing.size() - 1; i++) {
            if (!inline) sb.write(indentAt(baseIndent, step, i));
            sb.write(missing.get(i));
            sb.write(inline ? " { " : " {\n");
        }
        String leaf = missing.get(missing.size() - 1);
        int depth = missing.size() - 1;
        if (!inline) sb.write(indentAt(baseIndent, step, depth));
        sb.write(leafMap && needsQuoting(leaf) ? Token.quote(leaf) : leaf);
        sb.write(leafMap ? ": " : " = ");
        sb.write(renderValue(v, indentAt(baseIndent, step, depth), step));
        for (int i = missing.size() - 2; i >= 0; i--) {
            if (inline) {
                sb.write(" }");
            } else {
                sb.write('\n');
                sb.write(indentAt(baseIndent, step, i));
                sb.write('}');
            }
        }
        return sb.bytes();
    }

    private static String indentAt(String base, String step, int depth) {
        return base + step.repeat(depth);
    }

    /** {@code v} formatted on its own, its continuation lines prefixed with {@code linePrefix}. */
    private static byte[] renderValue(Ast.Value v, String linePrefix, String step) {
        String out = Format.formatValue(v, step);
        if (!linePrefix.isEmpty() && out.indexOf('\n') >= 0) out = out.replace("\n", "\n" + linePrefix);
        return out.getBytes(StandardCharsets.UTF_8);
    }

    private String siblingIndent(Target t) {
        int containerPos = t.container() != null ? t.container().pos().offset() : -1;
        return childIndent(t.containerEntries(), containerPos);
    }

    /** A new child's indent: the first own-line child's, else the block's own line plus one level. */
    private String childIndent(List<Ast.Entry> entries, int containerPos) {
        if (!entries.isEmpty()) {
            int first = entries.get(0).pos().offset();
            int ls = lineStartOffset(src, first);
            if (isBlank(src, ls, first)) return str(ls, first);
        }
        if (containerPos < 0) return "";
        return lineIndentAt(containerPos) + "  ";
    }

    /** The indent step for content among {@code entries}: a nested block there, the document's, else two spaces. */
    private String stepFor(List<Ast.Entry> entries) {
        String s = scanIndentStep(entries);
        return !s.isEmpty() ? s : indentStep();
    }

    private String indentStep() {
        String s = scanIndentStep(doc.entries());
        return !s.isEmpty() ? s : "  ";
    }

    /** What the first own-line child of the first nested block in {@code entries} adds to that block's line, or "". */
    private String scanIndentStep(List<Ast.Entry> entries) {
        for (Ast.Entry e : entries) {
            List<Ast.Entry> kids = List.of();
            if (e instanceof Ast.Block b) kids = b.entries();
            else if (entryValue(e) instanceof Ast.BlockVal bv) kids = bv.entries();
            if (kids.isEmpty()) continue;
            String base = lineLeadingIndent(e.pos().offset());
            int first = kids.get(0).pos().offset();
            int ls = lineStartOffset(src, first);
            if (isBlank(src, ls, first)) {
                String ci = str(ls, first);
                if (ci.length() > base.length() && ci.startsWith(base)) return ci.substring(base.length());
            }
            String s = scanIndentStep(kids);
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    /** The whitespace before {@code off} on its line, or "" when anything else precedes it. */
    private String lineIndentAt(int off) {
        int ls = lineStartOffset(src, off);
        return isBlank(src, ls, off) ? str(ls, off) : "";
    }

    /** The leading whitespace of the line holding {@code off}, whatever else precedes it. */
    private String lineLeadingIndent(int off) {
        int ls = lineStartOffset(src, off);
        int i = ls;
        while (i < off && (src[i] == ' ' || src[i] == '\t')) i++;
        return str(ls, i);
    }

    /** {@code e} formatted for a multi-line block: each line prefixed with {@code baseIndent}, no trailing newline. */
    private static byte[] renderEntryLines(Ast.Entry e, String baseIndent, String step) {
        String out = Format.formatEntries(List.of(e), step);
        if (out.endsWith("\n")) out = out.substring(0, out.length() - 1);
        if (!baseIndent.isEmpty()) out = baseIndent + out.replace("\n", "\n" + baseIndent);
        return out.getBytes(StandardCharsets.UTF_8);
    }

    /** {@code e} on one line, for an inline block: {@code a = 1}, {@code "k": "v"}, {@code n { a = 1 }}. */
    private static void writeEntryInline(Buf buf, Ast.Entry e) {
        switch (e) {
            case Ast.Assignment a -> {
                buf.write(a.keyQuoted() ? Token.quote(a.key()) : a.key());
                buf.write(" = ");
                buf.write(Format.formatValue(a.value(), "  "));
            }
            case Ast.MapEntry m -> {
                buf.write(Format.mapKeyQuoted(m) ? Token.quote(m.key()) : m.key());
                buf.write(": ");
                buf.write(Format.formatValue(m.value(), "  "));
            }
            case Ast.TypeEntry t -> buf.write("@type = " + Token.quote(t.url()));
            case Ast.Block b -> {
                buf.write(b.nameQuoted() ? Token.quote(b.name()) : b.name());
                buf.write(" {");
                for (Ast.Entry c : b.entries()) {
                    buf.write(' ');
                    writeEntryInline(buf, c);
                }
                buf.write(" }");
            }
        }
    }

    private static boolean hasMapEntry(List<Ast.Entry> entries) {
        for (Ast.Entry e : entries) if (e instanceof Ast.MapEntry) return true;
        return false;
    }

    // -- keyed collections (draft -01 §3.13) ---------------------------------

    /**
     * Declares that the keyed collection at {@code fieldPath} uses
     * {@code keyField} as its (pxf.key) field, so the keyed editors can also
     * address it written in the anonymous list form
     * ({@code fieldPath = [ { keyField = "k" } ]}). Matched by exact path; a
     * later call replaces an earlier one.
     */
    public Rewriter keyedByField(String fieldPath, String keyField) {
        keyFields.put(fieldPath, keyField);
        return this;
    }

    /** One binding of a keyed field: a keyed block, or an anonymous list. */
    private record Binding(boolean anon, int scopeStart, int closeOff, List<Ast.Entry> entries, Ast.ListVal list, String keyField) {
        /** Every addressable element, in document order. */
        List<Elem> elems() {
            List<Elem> out = new ArrayList<>();
            if (anon) {
                for (int i = 0; i < list.elements().size(); i++) {
                    if (!(list.elements().get(i) instanceof Ast.BlockVal bv)) continue;
                    String key = explicitKeyOf(bv.entries(), keyField);
                    if (key == null || key.isEmpty()) continue;
                    out.add(new Elem(this, key, null, i, bv, bv.entries()));
                }
                return out;
            }
            for (Ast.Entry e : entries) {
                if (e instanceof Ast.Block n) {
                    out.add(new Elem(this, n.name(), n, -1, null, n.entries()));
                } else if (e instanceof Ast.Assignment n && n.value() instanceof Ast.BlockVal bv) {
                    out.add(new Elem(this, n.key(), n, -1, bv, bv.entries()));
                }
            }
            return out;
        }
    }

    /** One element of a binding: its entry (block form) or its index and value (anonymous form), and its body. */
    private record Elem(Binding b, String key, Ast.Entry node, int idx, Ast.BlockVal bv, List<Ast.Entry> body) {
        /** The entry whose body holds the element; for an anonymous element, an assignment over its value, for its span. */
        Ast.Entry container() {
            if (node != null) return node;
            return new Ast.Assignment(bv.pos(), "", bv, List.of(), "", false, bv.pos(), bv.end());
        }
    }

    /** The value of the one explicit, unquoted string assignment to {@code keyName}, or null. */
    private static String explicitKeyOf(List<Ast.Entry> entries, String keyName) {
        String key = null;
        for (Ast.Entry e : entries) {
            if (!(e instanceof Ast.Assignment a) || !a.key().equals(keyName)) continue;
            if (!(a.value() instanceof Ast.StringVal sv) || a.keyQuoted() || key != null) return null;
            key = sv.value();
        }
        return key;
    }

    private record Scope(List<Ast.Entry> entries, String key) {}

    private Scope keyedScopeEntries(String fieldPath) {
        List<String> segs = splitPathSegs(fieldPath);
        if (segs == null) throw new IllegalStateException("invalid path " + Token.quote(fieldPath));
        String key = segs.get(segs.size() - 1);
        if (segs.size() == 1) return new Scope(doc.entries(), key);
        String parent = String.join(".", segs.subList(0, segs.size() - 1));
        Target t;
        try {
            t = resolveIn(doc.entries(), null, parent);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage());
        }
        if (t.entry() == null) throw new IllegalStateException("no entry at " + Token.quote(parent));
        if (t.entry() instanceof Ast.Block b) return new Scope(b.entries(), key);
        if (entryValue(t.entry()) instanceof Ast.BlockVal bv) return new Scope(bv.entries(), key);
        throw new IllegalStateException(Token.quote(parent) + " is not a block");
    }

    /** Every binding of the keyed field at {@code fieldPath}, in document order. */
    private List<Binding> keyedBindings(String op, String fieldPath) {
        Scope scope;
        try {
            scope = keyedScopeEntries(fieldPath);
        } catch (IllegalStateException e) {
            throw fail("pxf: " + op + ": " + e.getMessage());
        }
        String keyField = keyFields.getOrDefault(fieldPath, "");
        List<Binding> bs = new ArrayList<>();
        boolean sawUndeclaredList = false;
        for (Ast.Entry e : scope.entries()) {
            if (!entryKey(e).equals(scope.key())) continue;
            if (e instanceof Ast.Block n) {
                bs.add(new Binding(false, n.pos().offset(), n.end().offset() - 1, n.entries(), null, ""));
            } else if (e instanceof Ast.Assignment n) {
                if (n.value() instanceof Ast.BlockVal v) {
                    bs.add(new Binding(false, n.pos().offset(), v.end().offset() - 1, v.entries(), null, ""));
                } else if (n.value() instanceof Ast.ListVal v) {
                    if (keyField.isEmpty()) {
                        sawUndeclaredList = true;
                        continue;
                    }
                    bs.add(new Binding(true, n.pos().offset(), v.end().offset() - 1, List.of(), v, keyField));
                }
            }
        }
        if (bs.isEmpty()) {
            if (sawUndeclaredList) {
                throw fail("pxf: " + op + ": " + Token.quote(fieldPath)
                        + " is written in the anonymous list form; declare its key field with KeyedByField to edit it");
            }
            throw fail("pxf: " + op + ": no keyed collection at " + Token.quote(fieldPath));
        }
        for (Binding b : bs) {
            byte want = (byte) (b.anon() ? ']' : '}');
            if (b.closeOff() < b.scopeStart() || b.closeOff() >= src.length || src[b.closeOff()] != want) {
                throw fail("pxf: " + op + ": binding at " + Token.quote(fieldPath) + " has no source span");
            }
        }
        return bs;
    }

    private static Elem findKeyedElem(List<Binding> bs, String key) {
        for (Binding b : bs) {
            for (Elem e : b.elems()) if (e.key().equals(key)) return e;
        }
        return null;
    }

    private static Ast.Block newKeyedElementBlock(String key, List<Ast.Entry> body) {
        return new Ast.Block(Position.UNKNOWN, key, body, List.of(), "", !Format.identSafeEntryName(key));
    }

    private static Ast.BlockVal newAnonElement(String keyField, String key, List<Ast.Entry> body) {
        List<Ast.Entry> entries = new ArrayList<>(body.size() + 1);
        entries.add(new Ast.Assignment(Position.UNKNOWN, keyField, new Ast.StringVal(Position.UNKNOWN, key), List.of(), ""));
        entries.addAll(body);
        return new Ast.BlockVal(Position.UNKNOWN, entries);
    }

    /**
     * Stages an upsert of the field at {@code subpath} in the element named
     * {@code key} of the keyed collection at {@code fieldPath}: {@link #set}
     * inside the element. When no element has that key, one is appended to
     * the collection's last binding, in its form.
     */
    public void setKeyed(String fieldPath, String key, String subpath, Ast.Value v) {
        String op = "SetKeyed " + fieldPath + "[" + Token.quote(key) + "]." + subpath;
        if (v == null) throw fail("pxf: " + op + ": nil value");
        if (containsBadVal(v)) throw fail("pxf: " + op + ": cannot write a BadVal placeholder");
        if (key.isEmpty()) throw fail("pxf: " + op + ": empty key");
        List<Binding> bs = keyedBindings(op, fieldPath);
        String pathKey = "\0keyed:set:" + fieldPath + "\0" + key + "\0" + subpath;
        Elem elem = findKeyedElem(bs, key);
        if (elem == null) {
            stageSplice(pathKey, renderAppendKeyedElement(bs.get(bs.size() - 1), key, buildEntryChain(op, subpath, v)));
            return;
        }
        setResolved(op, pathKey, resolveIn(elem.body(), elem.container(), subpath), v);
    }

    private Splice renderAppendKeyedElement(Binding b, String key, List<Ast.Entry> body) {
        if (b.anon()) return renderAppendListElement(b, newAnonElement(b.keyField(), key, body));
        Insert ins = renderAppendEntry(b.scopeStart(), b.closeOff(), b.entries(), newKeyedElementBlock(key, body));
        return new Splice(ins.at(), ins.text(), -1);
    }

    /** {@code a.b.c} and a value as {@code a { b { c = v } }}. */
    private static List<Ast.Entry> buildEntryChain(String op, String subpath, Ast.Value v) {
        List<String> segs = splitPathSegs(subpath);
        if (segs == null) throw fail("pxf: " + op + ": invalid subpath " + Token.quote(subpath) + ": empty segment");
        for (String seg : segs) {
            if (needsQuoting(seg)) throw fail("pxf: " + op + ": segment " + Token.quote(seg) + " is not a valid field name");
        }
        Ast.Entry e = new Ast.Assignment(Position.UNKNOWN, segs.get(segs.size() - 1), v, List.of(), "");
        for (int i = segs.size() - 2; i >= 0; i--) {
            e = new Ast.Block(Position.UNKNOWN, segs.get(i), List.of(e), List.of(), "");
        }
        return List.of(e);
    }

    /** Stages the removal of the field at {@code subpath} in the element named {@code key}: {@link #remove} inside it. */
    public void removeKeyed(String fieldPath, String key, String subpath) {
        String op = "RemoveKeyed " + fieldPath + "[" + Token.quote(key) + "]." + subpath;
        List<Binding> bs = keyedBindings(op, fieldPath);
        Elem elem = findKeyedElem(bs, key);
        if (elem == null) throw fail("pxf: " + op + ": no element " + Token.quote(key));
        Target t = resolveIn(elem.body(), elem.container(), subpath);
        if (t.entry() == null) throw fail("pxf: " + op + ": no such entry");
        int[] span = entryRemovalSpan(t.entry());
        stage(new SpanEdit("\0keyed:rm:" + fieldPath + "\0" + key + "\0" + subpath, span[0], span[1], new byte[0]));
    }

    /**
     * Stages the removal of the element named {@code key}. In the block form
     * it goes as {@link #remove} takes an entry; in the anonymous form the
     * element, its separating comma and a trailing comment on its last line
     * go, and the list stays well formed.
     */
    public void removeKeyedElement(String fieldPath, String key) {
        String op = "RemoveKeyedElement " + fieldPath + "[" + Token.quote(key) + "]";
        List<Binding> bs = keyedBindings(op, fieldPath);
        Elem elem = findKeyedElem(bs, key);
        if (elem == null) throw fail("pxf: " + op + ": no element " + Token.quote(key));
        String pathKey = "\0keyed:rmel:" + fieldPath + "\0" + key;
        if (elem.b().anon()) {
            stageListRemoval(pathKey, listElementRemovalSpans(elem.b().list(), elem.idx(), false));
            return;
        }
        int[] span = entryRemovalSpan(elem.node());
        stage(new SpanEdit(pathKey, span[0], span[1], new byte[0]));
    }

    /**
     * Stages a new element named {@code key} with {@code body}: appended to
     * the last binding when {@code before} is "", else just before the element
     * named {@code before}, in its binding's form. A key that already names an
     * element is refused.
     */
    public void insertKeyedElement(String fieldPath, String key, String before, List<Ast.Entry> body) {
        String op = "InsertKeyedElement " + fieldPath + "[" + Token.quote(key) + "]";
        if (key.isEmpty()) throw fail("pxf: " + op + ": empty key");
        for (Ast.Entry e : body) {
            if (e == null) throw fail("pxf: " + op + ": nil body entry");
            if (entryContainsBadVal(e)) throw fail("pxf: " + op + ": cannot write a BadVal placeholder");
        }
        List<Binding> bs = keyedBindings(op, fieldPath);
        if (findKeyedElem(bs, key) != null) throw fail("pxf: " + op + ": element " + Token.quote(key) + " already exists");
        String pathKey = "\0keyed:ins:" + fieldPath + "\0" + key;
        if (before.isEmpty()) {
            stageSplice(pathKey, renderAppendKeyedElement(bs.get(bs.size() - 1), key, body));
            return;
        }
        Elem anchor = findKeyedElem(bs, before);
        if (anchor == null) throw fail("pxf: " + op + ": no element " + Token.quote(before) + " to insert before");
        if (anchor.b().anon()) {
            stageSplice(pathKey, renderInsertListElementBefore(anchor.b(), anchor.idx(), newAnonElement(anchor.b().keyField(), key, body)));
            return;
        }
        Insert ins = renderInsertBlockElementBefore(anchor.b(), anchor.node(), newKeyedElementBlock(key, body));
        stage(new SpanEdit(pathKey, ins.at(), ins.at(), ins.text()));
    }

    private Insert renderInsertBlockElementBefore(Binding b, Ast.Entry anchor, Ast.Block nb) {
        int aStart = anchor.pos().offset();
        int ls = lineStartOffset(src, aStart);
        if (isBlank(src, ls, aStart)) {
            String indent = str(ls, aStart);
            Buf sb = new Buf();
            sb.write(renderEntryLines(nb, indent, stepFor(b.entries())));
            sb.write('\n');
            return new Insert(extendOverLeadingComments(ls), sb.bytes());
        }
        Buf sb = new Buf();
        writeEntryInline(sb, nb);
        sb.write(' ');
        return new Insert(aStart, sb.bytes());
    }

    /**
     * Stages renaming the element {@code oldKey} to {@code newKey}: in the
     * block form only the name token, quoted when it is not identifier-safe;
     * in the anonymous form the key field's string. A key that names another
     * element is refused; renaming to the same key stages nothing.
     */
    public void renameKeyedElement(String fieldPath, String oldKey, String newKey) {
        String op = "RenameKeyedElement " + fieldPath + "[" + Token.quote(oldKey) + "]";
        if (newKey.isEmpty()) throw fail("pxf: " + op + ": empty new key");
        if (newKey.equals(oldKey)) return;
        List<Binding> bs = keyedBindings(op, fieldPath);
        Elem elem = findKeyedElem(bs, oldKey);
        if (elem == null) throw fail("pxf: " + op + ": no element " + Token.quote(oldKey));
        if (findKeyedElem(bs, newKey) != null) throw fail("pxf: " + op + ": element " + Token.quote(newKey) + " already exists");
        String pathKey = "\0keyed:ren:" + fieldPath + "\0" + oldKey;
        if (elem.b().anon()) {
            Ast.Assignment ka = anonKeyAssignment(elem.bv().entries(), elem.b().keyField());
            if (ka == null) throw fail("pxf: " + op + ": element has no " + Token.quote(elem.b().keyField()) + " assignment");
            stage(new SpanEdit(pathKey, ka.value().pos().offset(), ka.value().end().offset(),
                    Token.quote(newKey).getBytes(StandardCharsets.UTF_8)));
            return;
        }
        int start = elem.node().pos().offset();
        int end = nameTokenEnd(src, start);
        if (end < 0) throw fail("pxf: " + op + ": element has no source span");
        String text = Format.identSafeEntryName(newKey) ? newKey : Token.quote(newKey);
        stage(new SpanEdit(pathKey, start, end, text.getBytes(StandardCharsets.UTF_8)));
    }

    private static Ast.Assignment anonKeyAssignment(List<Ast.Entry> entries, String keyField) {
        for (Ast.Entry e : entries) {
            if (e instanceof Ast.Assignment a && !a.keyQuoted() && a.key().equals(keyField) && a.value() instanceof Ast.StringVal) {
                return a;
            }
        }
        return null;
    }

    /**
     * Stages moving the element named {@code key}, its source verbatim, to
     * the end of the collection when {@code before} is "", else just before
     * the element named {@code before}. In the block form its glued leading
     * comment lines go with it; in the anonymous form a trailing comment on
     * its line goes when the new place is a line of its own. An element and
     * its anchor must share a surface form.
     */
    public void moveKeyedElement(String fieldPath, String key, String before) {
        String op = "MoveKeyedElement " + fieldPath + "[" + Token.quote(key) + "]";
        if (before.equals(key)) throw fail("pxf: " + op + ": cannot move an element before itself");
        List<Binding> bs = keyedBindings(op, fieldPath);
        Elem elem = findKeyedElem(bs, key);
        if (elem == null) throw fail("pxf: " + op + ": no element " + Token.quote(key));
        Elem anchor = null;
        if (!before.isEmpty()) {
            anchor = findKeyedElem(bs, before);
            if (anchor == null) throw fail("pxf: " + op + ": no element " + Token.quote(before) + " to move before");
            if (anchor.b().anon() != elem.b().anon()) {
                throw fail("pxf: " + op + ": element " + Token.quote(key) + " and anchor " + Token.quote(before)
                        + " are in different surface forms");
            }
        }
        if (elem.b().anon()) moveListElement(fieldPath, elem, anchor);
        else moveBlockElement(op, fieldPath, elem, anchor);
    }

    private void moveBlockElement(String op, String fieldPath, Elem elem, Elem anchor) {
        int[] span = entryRemovalSpan(elem.node());
        int start = span[0], end = span[1];
        boolean wholeLines = span[2] == 1;
        if (wholeLines) start = extendOverLeadingComments(start);
        byte[] text = Arrays.copyOfRange(src, start, end);
        String delKey = "\0keyed:mv:" + fieldPath + "\0" + elem.key();
        String insKey = delKey + "\0ins";
        if (!wholeLines) {
            if (text.length == 0 || text[text.length - 1] != ' ') text = concat(text, " ");
            int at = elem.b().closeOff();
            if (anchor != null) {
                at = anchor.node().pos().offset();
            } else if (at > 0 && src[at - 1] != ' ' && src[at - 1] != '\t') {
                text = concat(" ".getBytes(StandardCharsets.UTF_8), text);
            }
            stage(new SpanEdit(delKey, start, end, new byte[0]));
            stage(new SpanEdit(insKey, at, at, text));
            return;
        }
        if (text.length == 0 || text[text.length - 1] != '\n') text = concat(text, "\n");
        int at;
        if (anchor != null) {
            int aStart = anchor.node().pos().offset();
            int ls = lineStartOffset(src, aStart);
            if (!isBlank(src, ls, aStart)) throw fail("pxf: " + op + ": anchor element does not start its own line");
            at = extendOverLeadingComments(ls);
        } else {
            at = elem.b().closeOff();
            int ls = lineStartOffset(src, elem.b().closeOff());
            if (isBlank(src, ls, elem.b().closeOff())) {
                at = ls;
            } else {
                byte[] trimmed = text[text.length - 1] == '\n' ? Arrays.copyOf(text, text.length - 1) : text;
                text = concat(concat("\n".getBytes(StandardCharsets.UTF_8), trimmed), "\n");
            }
        }
        stage(new SpanEdit(delKey, start, end, new byte[0]));
        stage(new SpanEdit(insKey, at, at, text));
    }

    private void moveListElement(String fieldPath, Elem elem, Elem anchor) {
        Ast.ListVal lv = elem.b().list();
        // Already where it was asked to go: the input stands.
        if (anchor == null && elem.idx() == lv.elements().size() - 1) return;
        if (anchor != null && anchor.b().list() == lv && anchor.idx() == elem.idx() + 1) return;
        byte[] text = Arrays.copyOfRange(src, elem.bv().pos().offset(), elem.bv().end().offset());
        String delKey = "\0keyed:mv:" + fieldPath + "\0" + elem.key();
        // A line comment would swallow what follows it on an inline target,
        // so it goes along only to a line of the element's own.
        Binding to = anchor != null ? anchor.b() : elem.b();
        boolean onOwnLine = listMultiline(to.list());
        if (anchor != null) {
            int p = to.list().elements().get(anchor.idx()).pos().offset();
            onOwnLine = onOwnLine && isBlank(src, lineStartOffset(src, p), p);
        }
        byte[] comment = new byte[0];
        if (onOwnLine) {
            int[] c = trailingComment(listElemTail(lv, elem.idx()));
            if (c[0] >= 0) comment = Arrays.copyOfRange(src, c[0], c[1]);
        }
        Splice ins = anchor != null
                ? spliceListElementBefore(anchor.b(), anchor.idx(), text, comment)
                : spliceListElementAppend(elem.b(), text, comment);
        stageListRemoval(delKey, listElementRemovalSpans(lv, elem.idx(), !onOwnLine));
        stageSplice(delKey + "\0ins", ins);
    }

    // -- anonymous-list element surgery ----------------------------------------

    private boolean listMultiline(Ast.ListVal lv) {
        return indexOf(src, lv.pos().offset(), lv.end().offset(), (byte) '\n') >= 0;
    }

    private String listElementIndent(Ast.ListVal lv, int scopeStart) {
        if (!lv.elements().isEmpty()) {
            int p = lv.elements().get(0).pos().offset();
            int ls = lineStartOffset(src, p);
            if (isBlank(src, ls, p)) return str(ls, p);
        }
        return lineLeadingIndent(scopeStart) + indentStep();
    }

    /** The first comma in the gap {@code src[from, to)} between two list tokens, stepping over comments, or -1. */
    private int listComma(int from, int to) {
        int i = from;
        while (i < to) {
            if (src[i] == ',') return i;
            if (src[i] == '#' || startsWith(src, i, to, "//")) {
                while (i < to && src[i] != '\n') i++;
            } else if (startsWith(src, i, to, "/*")) {
                int n = indexOf(src, i, to, "*/");
                if (n < 0) return -1;
                i = n + 2;
            } else {
                i++;
            }
        }
        return -1;
    }

    private int listElemComma(Ast.ListVal lv, int idx) {
        int limit = lv.end().offset() - 1;
        if (idx < lv.elements().size() - 1) limit = lv.elements().get(idx + 1).pos().offset();
        return listComma(lv.elements().get(idx).end().offset(), limit);
    }

    /** Where a trailing comment of element {@code idx} would start: past its comma on its last line, else past it. */
    private int listElemTail(Ast.ListVal lv, int idx) {
        int end = lv.elements().get(idx).end().offset();
        int c = listElemComma(lv, idx);
        if (c >= 0 && indexOf(src, end, c, (byte) '\n') < 0) return c + 1;
        return end;
    }

    /**
     * The comment that ends the line at {@code off}: {start, end} or -1s, and
     * the line's end, or -1 when something else follows on the line. A block
     * comment counts only when it closes on the line.
     */
    private int[] trailingComment(int off) {
        int i = off + trailingListPad(off);
        int start = -1, end = -1;
        if (i < src.length) {
            if (src[i] == '#' || startsWith(src, i, src.length, "//")) {
                start = i;
                while (i < src.length && src[i] != '\n') i++;
                end = i;
            } else if (startsWith(src, i, src.length, "/*")) {
                int n = indexOf(src, i, "*/");
                if (n >= 0 && indexOf(src, i, n, (byte) '\n') < 0) {
                    start = i;
                    end = n + 2;
                    i = end + trailingListPad(end);
                }
            }
        }
        if (i >= src.length || src[i] == '\n') return new int[] {start, end, i};
        return new int[] {-1, -1, -1};
    }

    private record Span(int start, int end) {}

    /**
     * The spans to delete for element {@code idx} of {@code lv}: the element,
     * the comma that keeps the list well formed, and a trailing comment on
     * its last line unless {@code keepComment}.
     */
    private List<Span> listElementRemovalSpans(Ast.ListVal lv, int idx, boolean keepComment) {
        List<Ast.Value> elems = lv.elements();
        int start = elems.get(idx).pos().offset();
        int end = elems.get(idx).end().offset();
        int comma = listElemComma(lv, idx);
        int tail = listElemTail(lv, idx);
        int[] tc = trailingComment(tail);
        int commentStart = tc[0], lineEnd = tc[2];
        if (keepComment && commentStart >= 0) lineEnd = -1;

        List<Span> out = new ArrayList<>();
        if (comma >= tail) { // on a later line than the element
            out.add(new Span(comma, comma + 1 + trailingListPad(comma + 1)));
        } else if (comma < 0 && idx > 0) {
            int prevEnd = elems.get(idx - 1).end().offset();
            int prev = listComma(prevEnd, start);
            if (commentStart < 0 && prev >= 0 && !gapHasComment(prevEnd, start)) {
                // Nothing to keep between the two: they close up.
                return List.of(new Span(prevEnd, end + trailingListPad(end)));
            }
            if (prev >= 0) out.add(new Span(prev, prev + 1));
        }
        int ls = lineStartOffset(src, start);
        if (lineEnd < 0) { // it shares its last line with what follows
            out.add(new Span(start, tail + trailingListPad(tail)));
        } else if (!isBlank(src, ls, start)) {
            while (start > ls && (src[start - 1] == ' ' || src[start - 1] == '\t')) start--;
            out.add(new Span(start, lineEnd));
        } else if (lineEnd < src.length) {
            out.add(new Span(ls, lineEnd + 1));
        } else {
            out.add(new Span(ls, lineEnd));
        }
        return out;
    }

    private boolean gapHasComment(int from, int to) {
        for (int i = from; i < to; i++) {
            byte c = src[i];
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r' && c != ',') return true;
        }
        return false;
    }

    private void stageListRemoval(String pathKey, List<Span> spans) {
        for (int i = 0; i < spans.size(); i++) {
            stage(new SpanEdit(pathKey + "\0" + i, spans.get(i).start(), spans.get(i).end(), new byte[0]));
        }
    }

    /** An insertion into a list, and where the comma the element before it needs goes when that is not here (-1 for none). */
    private record Splice(int at, byte[] text, int commaAt) {}

    private void stageSplice(String pathKey, Splice s) {
        stage(new SpanEdit(pathKey, s.at(), s.at(), s.text()));
        if (s.commaAt() >= 0) {
            stage(new SpanEdit(pathKey + "\0comma", s.commaAt(), s.commaAt(), ",".getBytes(StandardCharsets.UTF_8)));
        }
    }

    private int trailingListPad(int off) {
        int n = 0;
        while (off + n < src.length && (src[off + n] == ' ' || src[off + n] == '\t')) n++;
        return n;
    }

    private Splice renderAppendListElement(Binding b, Ast.BlockVal bv) {
        return spliceListElementAppend(b, renderListElement(b, bv), new byte[0]);
    }

    private Splice renderInsertListElementBefore(Binding b, int idx, Ast.BlockVal bv) {
        return spliceListElementBefore(b, idx, renderListElement(b, bv), new byte[0]);
    }

    private byte[] renderListElement(Binding b, Ast.BlockVal bv) {
        return renderValue(bv, listElementIndent(b.list(), b.scopeStart()), indentStep());
    }

    private Splice spliceListElementAppend(Binding b, byte[] elemText, byte[] comment) {
        Ast.ListVal lv = b.list();
        String indent = listElementIndent(lv, b.scopeStart());
        if (lv.elements().isEmpty()) {
            // An empty list: the element on its own line before the ']'.
            Buf sb = new Buf();
            sb.write('\n');
            sb.write(indent);
            sb.write(elemText);
            sb.write('\n');
            sb.write(lineLeadingIndent(b.scopeStart()));
            return new Splice(b.closeOff(), sb.bytes(), -1);
        }
        int last = lv.elements().size() - 1;
        int lastEnd = lv.elements().get(last).end().offset();
        int comma = listElemComma(lv, last);
        Buf sb = new Buf();
        if (!listMultiline(lv)) {
            if (comma >= 0) {
                sb.write(' ');
                sb.write(elemText);
                sb.write(',');
                return new Splice(comma + 1, sb.bytes(), -1);
            }
            sb.write(", ");
            sb.write(elemText);
            return new Splice(lastEnd, sb.bytes(), -1);
        }
        int after = comma >= 0 ? comma + 1 : lastEnd;
        int commentEnd = trailingComment(after)[1];
        int at = commentEnd >= 0 ? commentEnd : after;
        int commaAt = -1;
        if (comma < 0) {
            if (commentEnd >= 0) commaAt = lastEnd;
            else sb.write(',');
        }
        sb.write('\n');
        sb.write(indent);
        sb.write(elemText);
        if (comma >= 0) sb.write(',');
        if (comment.length > 0) {
            sb.write(' ');
            sb.write(comment);
        }
        return new Splice(at, sb.bytes(), commaAt);
    }

    private Splice spliceListElementBefore(Binding b, int idx, byte[] elemText, byte[] comment) {
        Ast.ListVal lv = b.list();
        String indent = listElementIndent(lv, b.scopeStart());
        int target = lv.elements().get(idx).pos().offset();
        Buf sb = new Buf();
        if (listMultiline(lv)) {
            int ls = lineStartOffset(src, target);
            if (isBlank(src, ls, target)) {
                // At a line start: the target's indent, then the comma and
                // newline, leaving the lines after it intact.
                sb.write(indent);
                sb.write(elemText);
                sb.write(',');
                if (comment.length > 0) {
                    sb.write(' ');
                    sb.write(comment);
                }
                sb.write('\n');
                return new Splice(extendOverLeadingComments(ls), sb.bytes(), -1);
            }
        }
        sb.write(elemText);
        sb.write(", ");
        return new Splice(target, sb.bytes(), -1);
    }

    // -- shared helpers --------------------------------------------------------

    /**
     * Walks a line start up over the whole-line {@code #} / {@code //}
     * comment lines glued to it, to the topmost. A blank line stops it, so a
     * group header set off by one stays.
     */
    private int extendOverLeadingComments(int start) {
        while (start > 0) {
            int ls = lineStartOffset(src, start - 1);
            String line = str(ls, start - 1).strip();
            if (line.isEmpty() || (line.charAt(0) != '#' && !line.startsWith("//"))) break;
            start = ls;
        }
        return start;
    }

    /** Just past the entry-name token at {@code start}: a string literal or an identifier, or -1. */
    private static int nameTokenEnd(byte[] src, int start) {
        if (start >= src.length) return -1;
        if (src[start] == '"') return skipString(src, start);
        int i = start;
        while (i < src.length && Lexer.isIdentPart(src[i])) i++;
        return i == start ? -1 : i;
    }

    /** Just past the string literal at {@code i}, triple-quoted or simple, or -1. protowire-go's skipDirString. */
    private static int skipString(byte[] in, int i) {
        if (i + 2 < in.length && in[i + 1] == '"' && in[i + 2] == '"') {
            for (int j = i + 3; j + 2 < in.length; j++) {
                if (in[j] == '"' && in[j + 1] == '"' && in[j + 2] == '"') return j + 3;
            }
            return -1;
        }
        int j = i + 1;
        while (j < in.length) {
            if (in[j] == '\\') {
                if (j + 1 >= in.length) return -1;
                j += 2;
                continue;
            }
            if (in[j] == '"') return j + 1;
            if (in[j] == '\n') return -1;
            j++;
        }
        return -1;
    }

    /** A dotted path's segments, or null when it is empty or has an empty segment. */
    private static List<String> splitPathSegs(String path) {
        if (path.isEmpty()) return null;
        String[] segs = path.split("\\.", -1);
        for (String s : segs) if (s.isEmpty()) return null;
        return List.of(segs);
    }

    private static int lineStartOffset(byte[] src, int off) {
        int i = off;
        while (i > 0 && src[i - 1] != '\n') i--;
        return i;
    }

    private static boolean isBlank(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) if (b[i] != ' ' && b[i] != '\t') return false;
        return true;
    }

    private String str(int from, int to) {
        return new String(src, from, to - from, StandardCharsets.UTF_8);
    }

    private static int indexOf(byte[] b, int from, int to, byte c) {
        for (int i = from; i < to; i++) if (b[i] == c) return i;
        return -1;
    }

    private static int indexOf(byte[] b, int from, String s) {
        return indexOf(b, from, b.length, s);
    }

    private static int indexOf(byte[] b, int from, int to, String s) {
        byte[] n = s.getBytes(StandardCharsets.UTF_8);
        for (int i = from; i + n.length <= to; i++) {
            if (startsWith(b, i, to, s)) return i;
        }
        return -1;
    }

    private static boolean startsWith(byte[] b, int i, int to, String s) {
        if (i + s.length() > to) return false;
        for (int k = 0; k < s.length(); k++) if (b[i + k] != (byte) s.charAt(k)) return false;
        return true;
    }

    private static byte[] concat(byte[] a, String s) {
        return concat(a, s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** A byte buffer for rendered text. */
    private static final class Buf {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void write(int c) { out.write(c); }

        void write(String s) { out.writeBytes(s.getBytes(StandardCharsets.UTF_8)); }

        void write(byte[] b) { out.writeBytes(b); }

        byte[] bytes() { return out.toByteArray(); }
    }
}
