// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grammar half of keyed repeated fields (draft -01 §3.13, #50):
 * {@code field_entry = (identifier | string), (assignment_tail |
 * block_tail)}. Quoted entry names parse everywhere — the grammar is
 * schema-independent — and round-trip through {@link Format} with their
 * spelling intact; the AST keeps the unquoted value and the flag.
 */
class ParserKeyedGrammarTest {

    @Test
    void quotedBlockNameParsesAndRoundTrips() {
        String input = "regions {\n  \"us-east-1\" {\n    replicas = 3\n  }\n}\n";
        Ast.Document doc = Parser.parse(input);
        Ast.Block regions = assertInstanceOf(Ast.Block.class, doc.entries().get(0));
        assertFalse(regions.nameQuoted());
        Ast.Block entry = assertInstanceOf(Ast.Block.class, regions.entries().get(0));
        assertEquals("us-east-1", entry.name());
        assertTrue(entry.nameQuoted());
        assertEquals(input, Format.formatDocument(doc));
    }

    @Test
    void quotedAssignmentKeyParsesAndRoundTrips() {
        String input = "\"us-east-1\" = {\n  replicas = 3\n}\n";
        Ast.Document doc = Parser.parse(input);
        Ast.Assignment a = assertInstanceOf(Ast.Assignment.class, doc.entries().get(0));
        assertEquals("us-east-1", a.key());
        assertTrue(a.keyQuoted());
        assertEquals(input, Format.formatDocument(doc));
    }

    // Integer and bool keys remain invalid at entry-name position; they are
    // map keys and take only ':'.
    @Test
    void integerAndBoolKeysStayInvalidAsEntryNames() {
        for (String doc : new String[] {"42 = 1\n", "42 { }\n", "true = 1\n", "true { }\n"}) {
            PxfException e = assertThrows(PxfException.class, () -> Parser.parse(doc), doc);
            assertTrue(e.getMessage().contains("identifier or string key"), e.getMessage());
        }
    }

    @Test
    void fiveArgumentConstructorsMeanBare() {
        Ast.Value v = new Ast.StringVal(Position.UNKNOWN, "v");
        assertFalse(new Ast.Assignment(Position.UNKNOWN, "k", v, java.util.List.of(), "").keyQuoted());
        assertFalse(new Ast.Block(Position.UNKNOWN, "k", java.util.List.of(), java.util.List.of(), "").nameQuoted());
    }
}
