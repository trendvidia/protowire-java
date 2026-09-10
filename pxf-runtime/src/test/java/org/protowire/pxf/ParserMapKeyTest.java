// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AST side of the map-key grammar (draft -01, {@code map-key =
 * identifier / string / integer / bool}; protowire#284, #76): fmt and the
 * lite tier parse before they bind, so the keyword must be a key here
 * too — and only with the ':' tail, exactly as an integer key is.
 */
class ParserMapKeyTest {

    private static List<Ast.Entry> mapEntries(String doc) {
        Ast.Document d = Parser.parse(doc);
        assertEquals(1, d.entries().size());
        Ast.Block blk = assertInstanceOf(Ast.Block.class, d.entries().get(0));
        return blk.entries();
    }

    @Test
    void keywordIsAMapKey() {
        List<Ast.Entry> es = mapEntries("m {\n  true: \"a\"\n  false: \"b\"\n}\n");
        assertEquals(2, es.size());
        for (int i = 0; i < 2; i++) {
            Ast.MapEntry me = assertInstanceOf(Ast.MapEntry.class, es.get(i));
            assertEquals(List.of("true", "false").get(i), me.key());
        }
    }

    @Test
    void integerAndStringKeysStillParse() {
        List<Ast.Entry> es = mapEntries("m {\n  1: \"a\"\n  \"true\": \"b\"\n}\n");
        assertEquals("1", ((Ast.MapEntry) es.get(0)).key());
        assertEquals("true", ((Ast.MapEntry) es.get(1)).key());
    }

    // A field assignment or a submessage block needs an identifier: the
    // keyword takes only the ':' tail.
    @Test
    void keywordTakesOnlyTheColonTail() {
        PxfException e = assertThrows(PxfException.class, () -> Parser.parse("true = 1\n"));
        assertTrue(e.getMessage().contains("requires an identifier key, got bool"), e.getMessage());
        e = assertThrows(PxfException.class, () -> Parser.parse("true { }\n"));
        assertTrue(e.getMessage().contains("requires an identifier key, got bool"), e.getMessage());
    }

    // fmt reproduces the keyword bare.
    @Test
    void formatReproducesTheKeywordBare() {
        String src = "m {\n  true: \"a\"\n  false: \"b\"\n}\n";
        assertEquals(src, Format.formatDocument(Parser.parse(src)));
    }
}
