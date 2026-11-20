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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Parser#parseTolerant} makes of each document what protowire-go's
 * {@code ParseTolerant} makes of it (#208): the same errors, at the same
 * positions, and the same tree. {@code tolerant/go.tsv} is Go's, written by
 * {@code scripts/go-tolerant/main.go} from {@code tolerant/cases.tsv}: Go's
 * own tolerant-parse test documents, every prefix of five documents typed top
 * to bottom, a document for each recovery the grammar has, and every document
 * of the spec corpus. The conformance job reruns it against protowire-go
 * main. This test writes Java's tree in the notation the generator writes
 * Go's in, and compares the two as text.
 */
class TolerantParseTest {

    private record Row(String name, byte[] doc, String errors, String tree) {}

    private static List<Row> goRows() throws IOException {
        List<Row> rows = new ArrayList<>();
        try (InputStream in = TolerantParseTest.class.getResourceAsStream("/tolerant/go.tsv")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String[] f = line.split("\t", -1);
                rows.add(new Row(f[0], Base64.getDecoder().decode(f[1]), f[2], f[3]));
            }
        }
        return rows;
    }

    @Test
    void eachDocumentGetsGosErrorsAndTree() throws IOException {
        List<Row> rows = goRows();
        assertEquals(1021, rows.size(), "go.tsv's documents");
        List<String> differ = new ArrayList<>();
        for (Row r : rows) {
            Parser.TolerantResult got;
            try {
                got = Parser.parseTolerant(r.doc());
            } catch (RuntimeException e) {
                differ.add(r.name() + "\n  threw " + e);
                continue;
            }
            String errors = errors(got.errors());
            String tree = Dump.document(got.document());
            if (!errors.equals(r.errors())) {
                differ.add(r.name() + "\n  go errors:   " + r.errors() + "\n  java errors: " + errors);
            } else if (!tree.equals(r.tree())) {
                differ.add(r.name() + "\n  go tree:   " + r.tree() + "\n  java tree: " + tree);
            }
        }
        assertTrue(differ.isEmpty(), differ.size() + " of " + rows.size() + " differ from Go:\n"
                + differ.stream().limit(15).collect(Collectors.joining("\n")));
    }

    static String errors(List<PxfException> errs) {
        List<String> out = new ArrayList<>();
        for (PxfException e : errs) {
            String prefix = e.position() + ": ";
            String msg = e.getMessage().startsWith(prefix) ? e.getMessage().substring(prefix.length()) : e.getMessage();
            out.add(Dump.pos(e.position()) + " " + Dump.q(msg));
        }
        return String.join(" | ", out);
    }

    /** A valid document's tolerant tree is its strict one, with no error. */
    @Test
    void aValidDocumentParsesAsParseParsesIt() throws IOException {
        int valid = 0;
        for (Row r : goRows()) {
            Ast.Document strict;
            try {
                strict = Parser.parse(r.doc());
            } catch (PxfException e) {
                continue;
            }
            Parser.TolerantResult t = Parser.parseTolerant(r.doc());
            assertEquals(List.of(), t.errors(), r.name());
            assertEquals(Dump.document(strict), Dump.document(t.document()), r.name());
            valid++;
        }
        assertTrue(valid > 300, "valid documents: " + valid);
    }

    /** Parse keeps its all-or-nothing contract on the shapes the tolerant parse recovers from. */
    @Test
    void parseStillRefusesWhatTheTolerantParseRecovers() {
        for (String src : List.of("port =", "type = \"", "server {\n  port = 8080\n", "xs = [1, 2", "a = 1\n}\nb = 2\n", "a: 1\n")) {
            assertThrows(PxfException.class, () -> Parser.parse(src), src);
        }
    }

    /**
     * A malformed directive gives one error, and recovery does not read the
     * rest of its line, or the rows of a dataset whose header failed, as
     * entries: protowire-go#312's table (#259). Each document here used to
     * give one error per stray token and lose what followed.
     */
    @Test
    void aMalformedDirectiveIsOneError() {
        record Case(String src, String err, String typeUrl, int entries) {}
        for (Case c : List.of(
                new Case("@proto 5\n@type a.v1.M\n\nx = 1\n", "1:8: expected '{', dotted identifier", "a.v1.M", 1),
                new Case("@proto a.b c\n@type a.v1.M\n\nx = 1\n", "1:12: expected '{' after @proto a.b", "a.v1.M", 1),
                new Case("@type 5\n\nx = 1\n", "1:7: expected type name after @type", "", 1),
                new Case("@dataset T 5\n(1)\n(2, \"a\")\n", "1:12: expected '(' to start @dataset column list", "", 0))) {
            Parser.TolerantResult t = Parser.parseTolerant(c.src().getBytes(StandardCharsets.UTF_8));
            assertEquals(1, t.errors().size(), c.src() + ": " + t.errors());
            assertTrue(t.errors().get(0).getMessage().startsWith(c.err()), t.errors().get(0).getMessage());
            assertEquals(c.typeUrl(), t.document().typeUrl(), c.src());
            assertEquals(c.entries(), t.document().entries().size(), c.src());
        }

        // A key on the directive's line that starts an entry still starts one:
        // the dangling-@type recovery reads `x = 1` as the first entry.
        Parser.TolerantResult t = Parser.parseTolerant("@type x = 1\ny = 2\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, t.errors().size(), t.errors().toString());
        assertEquals(2, t.document().entries().size());

        // A valid dataset after a failed one still parses.
        t = Parser.parseTolerant("@dataset T 5\n(1)\n@dataset U (a)\n(2)\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, t.errors().size(), t.errors().toString());
        assertEquals(1, t.document().datasets().size());
        assertEquals("U", t.document().datasets().get(0).type());
        assertEquals(1, t.document().datasets().get(0).rows().size());
    }

    @Test
    void anInputPastMaxMessageSizeIsOneError() {
        Parser.TolerantResult t = Parser.parseTolerant("a = 1".getBytes(StandardCharsets.UTF_8),
                DecodeLimits.defaults().withMaxMessageSize(3));
        assertEquals("1:1: input of 5 bytes exceeds MaxMessageSize=3", t.errors().get(0).getMessage());
        assertFalse(t.document() == null);
        assertEquals(List.of(), t.document().entries());
    }

    /** The tree notation scripts/go-tolerant/main.go writes protowire-go's tree in. */
    static final class Dump {
        static String pos(Position p) {
            return p.line() + ":" + p.column() + ":" + p.offset();
        }

        /** A node's start and end (just past its last byte). */
        static String span(Position start, Position end) {
            return pos(start) + "~" + pos(end);
        }

        static String q(String s) {
            StringBuilder b = new StringBuilder("\"");
            s.codePoints().forEach(r -> {
                if (r == '\\' || r == '"') b.append('\\').appendCodePoint(r);
                else if (r == '\n') b.append("\\n");
                else if (r == '\t') b.append("\\t");
                else if (r < 0x20 || r == 0x7f || r > 0x7e) b.append("\\u{").append(Integer.toHexString(r)).append('}');
                else b.appendCodePoint(r);
            });
            return b.append('"').toString();
        }

        static String comments(List<Ast.Comment> cs) {
            return cs.stream().map(c -> pos(c.pos()) + q(c.text())).collect(Collectors.joining(",", "[", "]"));
        }

        static String strs(List<String> ss) {
            return ss.stream().map(Dump::q).collect(Collectors.joining(",", "[", "]"));
        }

        static String body(byte[] b) {
            return b == null ? "-" : q(new String(b, StandardCharsets.UTF_8));
        }

        static String document(Ast.Document d) {
            StringBuilder b = new StringBuilder();
            b.append("doc type=").append(q(d.typeUrl())).append(" body=").append(d.bodyOffset()).append(" lead=").append(comments(d.leadingComments()));
            for (Ast.Directive x : d.directives()) {
                b.append(" dir(").append(pos(x.pos())).append(" @").append(q(x.name())).append(" prefixes=").append(strs(x.prefixes()))
                        .append(" type=").append(q(x.type())).append(" body=").append(body(x.body()))
                        .append(" lead=").append(comments(x.leadingComments())).append(')');
            }
            for (Ast.DatasetDirective x : d.datasets()) {
                b.append(" dataset(").append(pos(x.pos())).append(" type=").append(q(x.type())).append(" cols=").append(strs(x.columns()))
                        .append(" lead=").append(comments(x.leadingComments())).append(" rows=[");
                for (int i = 0; i < x.rows().size(); i++) {
                    Ast.DatasetRow r = x.rows().get(i);
                    if (i > 0) b.append(',');
                    b.append("row(").append(pos(r.pos())).append(" lead=").append(comments(r.leadingComments())).append(" cells=[");
                    for (int j = 0; j < r.cells().size(); j++) {
                        if (j > 0) b.append(',');
                        Ast.Value c = r.cells().get(j);
                        if (c == null) b.append('_');
                        else value(b, c);
                    }
                    b.append("])");
                }
                b.append("])");
            }
            for (Ast.ProtoDirective x : d.protos()) {
                b.append(" proto(").append(pos(x.pos())).append(" shape=").append(x.shape().ordinal()).append(" name=").append(q(x.typeName()))
                        .append(" body=").append(body(x.body())).append(" lead=").append(comments(x.leadingComments())).append(')');
            }
            b.append(" entries=");
            entries(b, d.entries());
            b.append(" trail=").append(comments(d.trailingComments()));
            return b.toString();
        }

        static void entries(StringBuilder b, List<Ast.Entry> es) {
            b.append('[');
            for (int i = 0; i < es.size(); i++) {
                if (i > 0) b.append(',');
                switch (es.get(i)) {
                    case Ast.Assignment e -> {
                        b.append("A(").append(span(e.pos(), e.end())).append(' ').append(q(e.key())).append(" quoted=").append(e.keyQuoted())
                                .append(" lead=").append(comments(e.leadingComments())).append(" tc=").append(q(e.trailingComment())).append(" = ");
                        value(b, e.value());
                        b.append(')');
                    }
                    case Ast.MapEntry e -> {
                        b.append("M(").append(span(e.pos(), e.end())).append(' ').append(q(e.key())).append(" quoted=").append(e.keyQuoted())
                                .append(" lead=").append(comments(e.leadingComments())).append(" tc=").append(q(e.trailingComment())).append(" : ");
                        value(b, e.value());
                        b.append(')');
                    }
                    case Ast.Block e -> {
                        b.append("B(").append(span(e.pos(), e.end())).append(' ').append(q(e.name())).append(" quoted=").append(e.nameQuoted())
                                .append(" lead=").append(comments(e.leadingComments())).append(' ');
                        entries(b, e.entries());
                        b.append(" trail=").append(comments(e.trailingComments())).append(')');
                    }
                    case Ast.TypeEntry e -> b.append("T(").append(span(e.pos(), e.end())).append(' ').append(q(e.url()))
                            .append(" lead=").append(comments(e.leadingComments())).append(" tc=").append(q(e.trailingComment())).append(')');
                }
            }
            b.append(']');
        }

        static void value(StringBuilder b, Ast.Value v) {
            if (v instanceof Ast.NullVal n) {
                b.append("Null(").append(span(n.pos(), n.end())).append(')');
                return;
            }
            if (v instanceof Ast.BadVal x) {
                b.append("Bad(").append(span(x.pos(), x.end())).append(')');
                return;
            }
            switch (v) {
                case Ast.StringVal x -> b.append("S(").append(span(x.pos(), x.end())).append(' ').append(q(x.value())).append(')');
                case Ast.IntVal x -> b.append("I(").append(span(x.pos(), x.end())).append(' ').append(q(x.raw())).append(" p=").append(q(x.prec())).append(')');
                case Ast.FloatVal x -> b.append("F(").append(span(x.pos(), x.end())).append(' ').append(q(x.raw())).append(" p=").append(q(x.prec())).append(')');
                case Ast.BoolVal x -> b.append("Bool(").append(span(x.pos(), x.end())).append(' ').append(x.value()).append(')');
                case Ast.BytesVal x -> b.append("Bytes(").append(span(x.pos(), x.end())).append(' ').append(Base64.getEncoder().encodeToString(x.value())).append(')');
                case Ast.TimestampVal x -> b.append("Ts(").append(span(x.pos(), x.end())).append(' ').append(q(x.raw())).append(')');
                case Ast.DurationVal x -> b.append("Dur(").append(span(x.pos(), x.end())).append(' ').append(q(x.raw())).append(')');
                case Ast.IdentVal x -> b.append("Id(").append(span(x.pos(), x.end())).append(' ').append(q(x.name())).append(')');
                case Ast.ListVal x -> {
                    b.append("List(").append(span(x.pos(), x.end())).append(" [");
                    for (int i = 0; i < x.elements().size(); i++) {
                        if (i > 0) b.append(',');
                        value(b, x.elements().get(i));
                    }
                    b.append("])");
                }
                case Ast.BlockVal x -> {
                    b.append("Blk(").append(span(x.pos(), x.end())).append(' ');
                    entries(b, x.entries());
                    b.append(" trail=").append(comments(x.trailingComments())).append(')');
                }
                default -> throw new AssertionError(v);
            }
        }
    }
}
