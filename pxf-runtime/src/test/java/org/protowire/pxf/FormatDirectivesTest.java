// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Format#formatDocument} keeps every directive, with its dataset
 * rows, in document order (trendvidia/protowire#558, #188). A port of
 * protowire-go's {@code format_directives_test.go}; the expected texts are
 * the ones protowire-go's {@code FormatDocument} writes for the same sources.
 */
class FormatDirectivesTest {

    /**
     * Formats {@code src}, checks that the output parses to the same
     * directives and is a fixed point of formatting, and returns it.
     */
    private static String formatRoundTrip(String src) {
        Ast.Document doc = Parser.parse(src);
        String out = Format.formatDocument(doc);
        Ast.Document again = Parser.parse(out);
        assertEquals(directiveShape(doc), directiveShape(again), out);
        assertEquals(out, Format.formatDocument(again), "formatting is a fixed point");
        return out;
    }

    /** What a document's directives say, without positions or comments. */
    private static List<Object> directiveShape(Ast.Document doc) {
        List<Object> out = new ArrayList<>();
        out.add(doc.typeUrl());
        for (Ast.Directive d : doc.directives()) {
            out.add("@" + d.name());
            out.add(d.prefixes());
            out.add(d.body() == null ? null : new String(d.body(), StandardCharsets.UTF_8));
        }
        for (Ast.DatasetDirective d : doc.datasets()) {
            out.add("@dataset");
            out.add(d.type());
            out.add(d.columns());
            for (Ast.DatasetRow r : d.rows()) {
                List<String> cells = new ArrayList<>();
                for (Ast.Value c : r.cells()) {
                    if (c == null) {
                        cells.add("<absent>");
                    } else {
                        StringBuilder sb = new StringBuilder();
                        Format.formatValue(sb, c, 0);
                        cells.add(sb.toString());
                    }
                }
                out.add(cells);
            }
        }
        for (Ast.ProtoDirective d : doc.protos()) {
            out.add("@proto");
            out.add(d.shape());
            out.add(d.typeName());
            out.add(new String(d.body(), StandardCharsets.UTF_8));
        }
        out.add(doc.entries().size());
        return out;
    }

    /** The order the directives come in, @type aside: what the shape above leaves out. */
    private static List<String> directiveOrder(Ast.Document doc) {
        List<String> out = new ArrayList<>();
        for (Ast.Directive d : doc.directives()) out.add(d.pos().offset() + " @" + d.name());
        for (Ast.DatasetDirective d : doc.datasets()) out.add(d.pos().offset() + " @dataset");
        for (Ast.ProtoDirective d : doc.protos()) out.add(d.pos().offset() + " @proto");
        out.sort((a, b) -> Integer.compare(
                Integer.parseInt(a.substring(0, a.indexOf(' '))), Integer.parseInt(b.substring(0, b.indexOf(' ')))));
        return out.stream().map(s -> s.substring(s.indexOf(' ') + 1)).toList();
    }

    @Test
    void dataset() {
        String out = formatRoundTrip("@dataset trades.v1.Trade (id, px)\n(1, 2.5)\n(2, 3.5)\n");
        assertEquals("@dataset trades.v1.Trade (id, px)\n(1, 2.5)\n(2, 3.5)\n", out);

        // An empty cell is an absent field and null a present one; both stay.
        out = formatRoundTrip("@dataset trades.v1.Trade (id, px, note)\n(1, 2.5, )\n(2, , null)\n");
        assertEquals("@dataset trades.v1.Trade (id, px, note)\n(1, 2.5, )\n(2, , null)\n", out);
    }

    @Test
    void entryAndNamedDirective() {
        String src = "@type a.v1.M\n@entry x { a = 1 }\n@header h.v1.H\n\nname = \"n\"\n";
        assertEquals(src, formatRoundTrip(src));
    }

    @Test
    void protoShapes() {
        String[][] cases = {
            {"@proto { string id = 1; }\n@dataset (id)\n(\"a\")\n", null},
            {"@proto a.v1.R { string id = 1; }\n@dataset a.v1.R (id)\n(\"a\")\n", null},
            // The triple quotes go on lines of their own (protowire-go#303).
            {"@proto \"\"\"syntax = \"proto3\"; message R { string id = 1; }\"\"\"\n@dataset R (id)\n(\"a\")\n",
                "@proto \"\"\"\nsyntax = \"proto3\"; message R { string id = 1; }\n\"\"\"\n@dataset R (id)\n(\"a\")\n"},
            // @type moves to the front: it binds no @proto, so this changes nothing.
            {"@proto b\"CgA=\"\n@type a.v1.M\n\nx = 1\n", "@type a.v1.M\n@proto b\"CgA=\"\n\nx = 1\n"},
        };
        for (String[] c : cases) {
            String want = c[1] == null ? c[0] : c[1];
            assertEquals(want, formatRoundTrip(c[0]), c[0]);
        }
    }

    /**
     * An anonymous @proto binds the next typed directive, so a @proto
     * written after a dataset in the source stays after it.
     */
    @Test
    void directivesKeepSourceOrder() {
        String src = "@dataset t.v1.T (id)\n(1)\n@proto { string id = 1; }\n@dataset (id)\n(\"a\")\n";
        assertEquals(src, formatRoundTrip(src));
    }

    @Test
    void directiveComments() {
        String out = formatRoundTrip("# about the rows\n@dataset t.v1.T (id)\n(1)\n# second\n@dataset t.v1.U (id)\n(2)\n");
        assertTrue(out.contains("# about the rows\n@dataset t.v1.T (id)"), out);
        assertTrue(out.contains("# second\n@dataset t.v1.U (id)"), out);
    }

    /** A document whose only directive is @type formats as it always has. */
    @Test
    void onlyTypeUnchanged() {
        Ast.Document doc = Parser.parse("# c\n@type a.v1.M\n\nx = 1\n");
        assertEquals("@type a.v1.M\n\n# c\nx = 1\n", Format.formatDocument(doc));
    }

    /**
     * A triple-quoted @proto is written with its delimiters on lines of their
     * own, and its body reads back byte for byte (protowire-go#303). A port of
     * protowire-go's {@code TestFormatDocument_ProtoSourceLayout}.
     */
    @Test
    void protoSourceLayout() {
        // The issue's document: the line breaks around the body stay.
        String src = "@proto \"\"\"\n  syntax = \"proto3\";\n  package fmt.v1;\n  message Doc { string name = 1; }\n\"\"\"\n@type fmt.v1.Doc\n";
        assertEquals("@type fmt.v1.Doc\n@proto \"\"\"\n  syntax = \"proto3\";\n  package fmt.v1;\n  message Doc { string name = 1; }\n\"\"\"\n",
                formatRoundTrip(src));

        String[][] cases = {
            // Written on the closing delimiter's line, the last quote would
            // open the closing """ early, and the output would not parse.
            {"body ends in a quote", "@proto \"\"\"\n  syntax = \"proto3\";\n  import \"google/protobuf/empty.proto\"\n  \"\"\"\n",
                "syntax = \"proto3\";\nimport \"google/protobuf/empty.proto\""},
            // Written on the closing delimiter's line, a last line of only
            // whitespace would be taken for the delimiter's indent and dropped.
            {"whitespace-only last line", "@proto \"\"\"syntax = \"proto3\";\n  \n\"\"\"\n", "syntax = \"proto3\";\n  "},
            {"leading line break", "@proto \"\"\"\n\nsyntax = \"proto3\";\"\"\"\n", "\nsyntax = \"proto3\";"},
            {"trailing line break", "@proto \"\"\"syntax = \"proto3\";\n\n\"\"\"\n", "syntax = \"proto3\";\n"},
            {"empty body", "@proto \"\"\"\"\"\"\n", ""},
        };
        for (String[] c : cases) {
            Ast.Document doc = Parser.parse(c[1]);
            assertEquals(1, doc.protos().size(), c[0]);
            assertEquals(c[2], new String(doc.protos().get(0).body(), StandardCharsets.UTF_8), c[0] + ": body as read");
            assertEquals("@proto \"\"\"\n" + c[2] + "\n\"\"\"\n", formatRoundTrip(c[1]), c[0]);
        }
    }

    /** #188, first input: a @proto before an @entry used to be dropped. */
    @Test
    void issue188ProtoIsKept() {
        String src = "@proto a.v1.P { int32 b = 1; }\n@entry x { a = 1 }\n";
        String out = formatRoundTrip(src);
        assertEquals(src, out);
        assertEquals(List.of("@proto", "@entry"), directiveOrder(Parser.parse(out)));
    }

    /** #188, second input: an @entry after a dataset used to be written before it. */
    @Test
    void issue188DatasetBeforeEntryKeepsOrder() {
        String src = "@dataset t.v1.T (id)\n(1)\n@entry x { a = 1 }\n";
        String out = formatRoundTrip(src);
        assertEquals(src, out);
        assertEquals(List.of("@dataset", "@entry"), directiveOrder(Parser.parse(out)));
    }
}
