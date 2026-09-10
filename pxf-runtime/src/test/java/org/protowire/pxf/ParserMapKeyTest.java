// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // The parser records whether a key was written quoted: the two
    // spellings of `true` denote different keys (#82).
    @Test
    void parserRecordsWhetherTheKeyWasQuoted() {
        List<Ast.Entry> es = mapEntries("m {\n  true: \"a\"\n  \"true\": \"b\"\n  1: \"c\"\n  \"1\": \"d\"\n  plain: \"e\"\n  \"plain\": \"f\"\n}\n");
        boolean[] want = {false, true, false, true, false, true};
        for (int i = 0; i < want.length; i++) {
            assertEquals(want[i], ((Ast.MapEntry) es.get(i)).keyQuoted(), "entry " + i);
        }
    }

    // A MapEntry built in code carries no document spelling; the
    // five-argument constructor is the pre-#82 shape and means "bare".
    @Test
    void fiveArgumentConstructorMeansBare() {
        Ast.MapEntry e = new Ast.MapEntry(Position.UNKNOWN, "k", new Ast.StringVal(Position.UNKNOWN, "v"), List.of(), "");
        assertFalse(e.keyQuoted());
    }

    // Format: a quoted key is unquoted only when identifier-safe and not a
    // keyword; a bare key stays bare; a code-built entry is written so it
    // reads back as the same key.
    @Test
    void formatSpellsKeysSoTheyReadBackAsTheSameKey() {
        String src = "m {\n  \"true\": \"a\"\n  \"123\": \"b\"\n  \"plain\": \"c\"\n  true: \"d\"\n  0: \"e\"\n}\n";
        String want = "m {\n  \"true\": \"a\"\n  \"123\": \"b\"\n  plain: \"c\"\n  true: \"d\"\n  0: \"e\"\n}\n";
        assertEquals(want, Format.formatDocument(Parser.parse(src)));

        Ast.Value v = new Ast.StringVal(Position.UNKNOWN, "v");
        List<Ast.Entry> built = List.of(
                new Ast.MapEntry(Position.UNKNOWN, "plain", v, List.of(), ""),
                new Ast.MapEntry(Position.UNKNOWN, "true", v, List.of(), ""),
                new Ast.MapEntry(Position.UNKNOWN, "42", v, List.of(), ""),
                new Ast.MapEntry(Position.UNKNOWN, "my key", v, List.of(), ""),
                new Ast.MapEntry(Position.UNKNOWN, "", v, List.of(), ""),
                new Ast.MapEntry(Position.UNKNOWN, "null", v, List.of(), ""));
        Ast.Document doc = Ast.Document.of("", List.of(new Ast.Block(Position.UNKNOWN, "m", built, List.of(), "")));
        assertEquals("m {\n  plain: \"v\"\n  true: \"v\"\n  42: \"v\"\n  \"my key\": \"v\"\n  \"\": \"v\"\n  \"null\": \"v\"\n}\n",
                Format.formatDocument(doc));
    }

    // A field assignment or a submessage block needs an identifier: the
    // keyword takes only the ':' tail.
    @Test
    void keywordTakesOnlyTheColonTail() {
        PxfException e = assertThrows(PxfException.class, () -> Parser.parse("true = 1\n"));
        assertTrue(e.getMessage().contains("requires an identifier or string key, got bool"), e.getMessage());
        e = assertThrows(PxfException.class, () -> Parser.parse("true { }\n"));
        assertTrue(e.getMessage().contains("requires an identifier or string key, got bool"), e.getMessage());
    }

    // fmt reproduces the keyword bare.
    @Test
    void formatReproducesTheKeywordBare() {
        String src = "m {\n  true: \"a\"\n  false: \"b\"\n}\n";
        assertEquals(src, Format.formatDocument(Parser.parse(src)));
    }
}
