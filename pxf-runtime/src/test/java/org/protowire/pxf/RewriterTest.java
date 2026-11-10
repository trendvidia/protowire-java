// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Rewriter} edits a document as protowire-go's {@code Rewriter} does,
 * byte for byte (#209). {@code rewrite/go.tsv} is Go's, written by
 * {@code scripts/go-rewrite/main.go} from {@code rewrite/cases.txt}: Go's own
 * rewriter tests (rewrite_test.go, node_edit_test.go and the rewrite_keyed
 * tests) as data, with Go's property tests expanded over its rewrite corpus
 * (a Set and a Remove of every leaf) and its anonymous-list comment matrix
 * expanded over every layout and edit. Each line holds the document, the
 * operations and Go's results: each operation's ok or error text, then the
 * document's bytes or error. This test runs each line through the Java
 * Rewriter and holds every result to Go's. The conformance job reruns the
 * table against protowire-go main.
 */
class RewriterTest {

    private record Row(String name, byte[] doc, List<List<String>> ops, List<String> results) {}

    private static String b64(String s) {
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    private static List<Row> goRows() throws IOException {
        List<Row> rows = new ArrayList<>();
        try (InputStream in = RewriterTest.class.getResourceAsStream("/rewrite/go.tsv")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String[] f = line.split("\t", -1);
                List<List<String>> ops = new ArrayList<>();
                String opsText = b64(f[2]);
                if (!opsText.isEmpty()) {
                    for (String op : opsText.split("\u001e", -1)) ops.add(List.of(op.split("\u001f", -1)));
                }
                rows.add(new Row(f[0], Base64.getDecoder().decode(f[1]), ops, List.of(b64(f[3]).split("\u001e", -1))));
            }
        }
        return rows;
    }

    private static Ast.Value value(String text) {
        if (text.equals("<nil>")) return null;
        Ast.Document d = Parser.parseTolerant(("x = " + text).getBytes(StandardCharsets.UTF_8)).document();
        return ((Ast.Assignment) d.entries().get(0)).value();
    }

    private static List<Ast.Entry> entries(String text) {
        if (text.isEmpty()) return List.of();
        return Parser.parseTolerant(text.getBytes(StandardCharsets.UTF_8)).document().entries();
    }

    /** The node a selector names, as the generator's find: "value:key#n", "block:name#n", "<unspanned>" or "<nil>". */
    private static Object find(Rewriter r, String sel, boolean block) {
        if (sel.equals("<nil>")) return null;
        if (sel.equals("<unspanned>")) {
            return block ? new Ast.Block(Position.UNKNOWN, "x", List.of(), List.of(), "") : new Ast.IntVal(Position.UNKNOWN, "9");
        }
        String kind = sel.substring(0, sel.indexOf(':'));
        String key = sel.substring(sel.indexOf(':') + 1, sel.indexOf('#'));
        int[] n = {Integer.parseInt(sel.substring(sel.indexOf('#') + 1))};
        Object[] got = {null};
        walk(r.document().entries(), kind, key, n, got);
        if (got[0] == null) throw new AssertionError("selector " + sel + " matches nothing");
        return got[0];
    }

    private static void walk(List<Ast.Entry> es, String kind, String key, int[] n, Object[] got) {
        for (Ast.Entry e : es) {
            switch (e) {
                case Ast.Assignment x -> {
                    if (kind.equals("value") && x.key().equals(key)) {
                        if (n[0] == 0 && got[0] == null) got[0] = x.value();
                        n[0]--;
                    }
                    visit(x.value(), kind, key, n, got);
                }
                case Ast.MapEntry x -> {
                    if (kind.equals("value") && x.key().equals(key)) {
                        if (n[0] == 0 && got[0] == null) got[0] = x.value();
                        n[0]--;
                    }
                    visit(x.value(), kind, key, n, got);
                }
                case Ast.Block x -> {
                    if (kind.equals("block") && x.name().equals(key)) {
                        if (n[0] == 0 && got[0] == null) got[0] = x;
                        n[0]--;
                    }
                    walk(x.entries(), kind, key, n, got);
                }
                default -> { }
            }
        }
    }

    private static void visit(Ast.Value v, String kind, String key, int[] n, Object[] got) {
        if (v instanceof Ast.BlockVal bv) walk(bv.entries(), kind, key, n, got);
        else if (v instanceof Ast.ListVal lv) for (Ast.Value el : lv.elements()) visit(el, kind, key, n, got);
    }

    private static void apply(Rewriter r, List<String> o) {
        switch (o.get(0)) {
            case "set" -> r.set(o.get(1), value(o.get(2)));
            case "remove" -> r.remove(o.get(1));
            case "replace" -> r.replaceValue((Ast.Value) find(r, o.get(1), false), value(o.get(2)));
            case "setspan" -> r.setSpan(Integer.parseInt(o.get(1)), Integer.parseInt(o.get(2)), o.get(3).getBytes(StandardCharsets.UTF_8));
            case "append" -> r.appendEntry((Ast.Block) find(r, o.get(1), true), o.get(2).equals("<nil>") ? null : entries(o.get(2)).get(0));
            case "keyedby" -> r.keyedByField(o.get(1), o.get(2));
            case "setkeyed" -> r.setKeyed(o.get(1), o.get(2), o.get(3), value(o.get(4)));
            case "rmkeyed" -> r.removeKeyed(o.get(1), o.get(2), o.get(3));
            case "rmel" -> r.removeKeyedElement(o.get(1), o.get(2));
            case "insel" -> r.insertKeyedElement(o.get(1), o.get(2), o.get(3), entries(o.get(4)));
            case "rename" -> r.renameKeyedElement(o.get(1), o.get(2), o.get(3));
            case "move" -> r.moveKeyedElement(o.get(1), o.get(2), o.get(3));
            default -> throw new AssertionError("unknown operation " + o.get(0));
        }
    }

    private static List<String> run(Row row) {
        Rewriter r;
        try {
            r = new Rewriter(row.doc());
        } catch (PxfException e) {
            return List.of("new-err " + e.getMessage());
        }
        List<String> res = new ArrayList<>();
        for (List<String> o : row.ops()) {
            try {
                apply(r, o);
                res.add("ok");
            } catch (IllegalArgumentException e) {
                res.add("err " + e.getMessage());
            }
        }
        try {
            res.add("bytes " + Base64.getEncoder().encodeToString(r.bytes()));
        } catch (IllegalStateException e) {
            res.add("err " + e.getMessage());
        }
        return res;
    }

    @Test
    void eachEditMakesGosBytes() throws IOException {
        List<Row> rows = goRows();
        assertEquals(408, rows.size(), "go.tsv's cases");
        List<String> differ = new ArrayList<>();
        int edited = 0;
        for (Row row : rows) {
            List<String> got;
            try {
                got = run(row);
            } catch (RuntimeException e) {
                differ.add(row.name() + "\n  threw " + e);
                continue;
            }
            if (!got.equals(row.results())) {
                differ.add(row.name() + "\n  go:   " + show(row.results()) + "\n  java: " + show(got));
            }
            String last = row.results().get(row.results().size() - 1);
            if (last.startsWith("bytes ") && !last.equals("bytes " + Base64.getEncoder().encodeToString(row.doc()))) edited++;
        }
        assertTrue(differ.isEmpty(), differ.size() + " of " + rows.size() + " differ from Go:\n"
                + differ.stream().limit(8).collect(Collectors.joining("\n")));
        assertTrue(edited > 300, "rows whose document Go changes: " + edited);
    }

    private static String show(List<String> results) {
        return results.stream().map(s -> s.startsWith("bytes ")
                ? "bytes " + new String(Base64.getDecoder().decode(s.substring(6)), StandardCharsets.UTF_8).replace("\n", "\\n")
                : s).collect(Collectors.joining(" || "));
    }

    /** The text {@link Rewriter#setSpan} stages is a copy: changing the caller's buffer afterwards changes nothing. */
    @Test
    void setSpanCopiesItsText() {
        Rewriter r = new Rewriter("a = 1\n".getBytes(StandardCharsets.UTF_8));
        byte[] text = "42".getBytes(StandardCharsets.UTF_8);
        r.setSpan(4, 5, text);
        text[0] = '9';
        text[1] = '9';
        assertArrayEquals("a = 42\n".getBytes(StandardCharsets.UTF_8), r.bytes());
    }

    @Test
    void keyedByFieldChains() {
        Rewriter r = new Rewriter("a = 1\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(r.keyedByField("regions", "name") == r);
    }
}
