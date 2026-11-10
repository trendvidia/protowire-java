// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A comment that no entry follows stays where it was: at the end of the
 * document, before a block's closing brace, and around dataset rows (#200,
 * protowire-go#279). It used to be dropped, or carried out of its block
 * onto the next entry. Each expected text is protowire-go v1.17.0's
 * FormatDocument of the document. The documents are Go's own #279 tests,
 * plus a few more cases.
 */
class ClosingCommentsTest {

    private static final Map<String, String> GO = new LinkedHashMap<>();
    static {
        // At the end of the document, after an entry, a directive or a row.
        GO.put("@type a.v1.M\nname = \"n\"\n# end of doc\n",
                "@type a.v1.M\n\nname = \"n\"\n# end of doc\n");
        GO.put("@type a.v1.M\n@flag\n# end of doc\n",
                "@type a.v1.M\n@flag\n# end of doc\n");
        GO.put("@dataset t.v1.T (id)\n(1)\n# end of doc\n",
                "@dataset t.v1.T (id)\n(1)\n# end of doc\n");
        GO.put("@type a.v1.M\nname = \"n\" # inline\n# a\n// b\n/* c */\n",
                "@type a.v1.M\n\nname = \"n\" # inline\n# a\n// b\n/* c */\n");
        GO.put("@type a.v1.M\n# only\n",
                "@type a.v1.M\n\n# only\n");
        GO.put("a = 1\n# end\n",
                "a = 1\n# end\n");
        GO.put("# only\n",
                "# only\n");
        GO.put("@type a.v1.M\n@x { k = 1\n # in x\n }\n# end\n",
                "@type a.v1.M\n@x { k = 1\n # in x\n }\n# end\n");
        // A directive's body is kept as written, its comments with it, so
        // they are not also the next entry's: Java used to write # in x twice.
        GO.put("@x { k = 1\n # in x\n }\ny = 2\n",
                "@x { k = 1\n # in x\n }\n\ny = 2\n");
        // Before a block's '}'. The first used to move out of m, onto y.
        GO.put("@type a.v1.M\n\nm {\n  k: 1\n  # end of m\n}\ny = 2\n",
                "@type a.v1.M\n\nm {\n  k: 1\n  # end of m\n}\ny = 2\n");
        GO.put("@type a.v1.M\n\nb {\n  x = 1\n  # end of b\n}\n",
                "@type a.v1.M\n\nb {\n  x = 1\n  # end of b\n}\n");
        GO.put("@type a.v1.M\n\nb {\n  # empty\n}\n",
                "@type a.v1.M\n\nb {\n  # empty\n}\n");
        GO.put("@type a.v1.M\n\nb = {\n  x = 1\n  # end of b\n}\n",
                "@type a.v1.M\n\nb = {\n  x = 1\n  # end of b\n}\n");
        GO.put("@type a.v1.M\n\na {\n  b {\n    x = 1\n    # end of b\n  }\n  # end of a\n}\n",
                "@type a.v1.M\n\na {\n  b {\n    x = 1\n    # end of b\n  }\n  # end of a\n}\n");
        GO.put("@type a.v1.M\n\na {\n  x = 1\n}\n# about y\ny = 2\n",
                "@type a.v1.M\n\na {\n  x = 1\n}\n# about y\ny = 2\n");
        GO.put("m = {\n  k: 1\n  # end of m\n}\n",
                "m = {\n  k: 1\n  # end of m\n}\n");
        GO.put("l = [{\n  x = 1\n  # end\n}]\n",
                "l = [\n  {\n    x = 1\n    # end\n  }\n]\n");
        GO.put("p {\n  @type = \"x.v1.T\"\n  # end of p\n}\n",
                "p {\n  @type = \"x.v1.T\"\n  # end of p\n}\n");
        GO.put("m {\n  x = 1 # tc\n  # end\n}\n",
                "m {\n  x = 1 # tc\n  # end\n}\n");
        // Around dataset rows. A row is written on one line, so a comment
        // inside it, or after it on its line, comes before the next row.
        GO.put("@dataset t.v1.T (id, m)\n(1, {\n  x = 1\n  # end of m\n})\n",
                "@dataset t.v1.T (id, m)\n(1, {\n  x = 1\n  # end of m\n})\n");
        GO.put("@dataset t.v1.T (id)\n# first\n(1)\n# between\n(2)\n# end\n",
                "@dataset t.v1.T (id)\n# first\n(1)\n# between\n(2)\n# end\n");
        GO.put("@dataset t.v1.T (a, b)\n(1, # one\n 2)\n(3, 4)\n",
                "@dataset t.v1.T (a, b)\n# one\n(1, 2)\n(3, 4)\n");
        GO.put("@dataset t.v1.T (a, b)\n(1, 2) # same line\n(3, 4)\n",
                "@dataset t.v1.T (a, b)\n(1, 2)\n# same line\n(3, 4)\n");
        GO.put("@dataset T (a, b)\n(1, 2)\n# about U\n@dataset U (z)\n(9)\n",
                "@dataset T (a, b)\n(1, 2)\n# about U\n@dataset U (z)\n(9)\n");
        // Inside a list, a comment has no node to hold it and moves after
        // the list, in both ports (protowire-go#282).
        GO.put("l = [\n  1, # one\n  2\n]\n",
                "l = [\n  1,\n  2\n]\n# one\n");
    }

    private static Ast.Document parse(String doc) {
        return Parser.parse(doc.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void formattingKeepsThemWhereGoKeepsThem() {
        assertEquals(25, GO.size());
        GO.forEach((doc, want) -> assertEquals(want, Format.formatDocument(parse(doc)), doc));
    }

    @Test
    void formattingTwiceChangesNothing() {
        GO.values().forEach(out -> assertEquals(out, Format.formatDocument(parse(out)), out));
    }

    @Test
    void theDocumentHoldsItsLastComments() {
        Ast.Document d = parse("@type a.v1.M\nname = \"n\"\n# end of doc\n");
        assertEquals(List.of("# end of doc"), texts(d.trailingComments()));
        assertEquals(List.of(), ((Ast.Assignment) d.entries().get(0)).leadingComments());
    }

    @Test
    void aBlockHoldsTheCommentsBeforeItsBrace() {
        Ast.Document d = parse("@type a.v1.M\nm {\n  k: 1\n  # end of m\n}\ny = 2\n");
        assertEquals(List.of("# end of m"), texts(((Ast.Block) d.entries().get(0)).trailingComments()));
        assertEquals(List.of(), ((Ast.Assignment) d.entries().get(1)).leadingComments());

        Ast.BlockVal v = (Ast.BlockVal) ((Ast.Assignment) parse("b = {\n  # empty\n}\n").entries().get(0)).value();
        assertEquals(List.of("# empty"), texts(v.trailingComments()));
    }

    @Test
    void aRowHoldsTheCommentsBeforeAndInsideIt() {
        Ast.Document d = parse("@dataset t.v1.T (a, b)\n# first\n(1, # one\n 2)\n# between\n(3, 4)\n# end\n");
        List<Ast.DatasetRow> rows = d.datasets().get(0).rows();
        assertEquals(List.of("# first", "# one"), texts(rows.get(0).leadingComments()));
        assertEquals(List.of("# between"), texts(rows.get(1).leadingComments()));
        assertEquals(List.of("# end"), texts(d.trailingComments()));
    }

    @Test
    void theOlderConstructorsHoldNone() {
        Position p = Position.UNKNOWN;
        assertEquals(List.of(), new Ast.Document("", List.of(), List.of(), List.of(), List.of(), List.of()).trailingComments());
        assertEquals(List.of(), Ast.Document.of("", List.of()).trailingComments());
        assertEquals(List.of(), new Ast.Block(p, "b", List.of(), List.of(), "").trailingComments());
        assertEquals(List.of(), new Ast.Block(p, "b", List.of(), List.of(), "", true).trailingComments());
        assertEquals(List.of(), new Ast.Block(p, "b", List.of(), List.of(), "", true, p).trailingComments());
        assertEquals(List.of(), new Ast.BlockVal(p, List.of()).trailingComments());
        assertEquals(List.of(), new Ast.DatasetRow(p, List.of()).leadingComments());
    }

    private static List<String> texts(List<Ast.Comment> cs) {
        return cs.stream().map(Ast.Comment::text).toList();
    }
}
