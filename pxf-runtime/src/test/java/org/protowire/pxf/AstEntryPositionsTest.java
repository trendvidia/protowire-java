// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The parser records where an assignment's {@code =}, a block's brace
 * and a type entry's URL stand, which a schema layer reports a refusal at,
 * as protowire-go does (#222). An entry built through the older
 * constructors carries its own position there.
 */
class AstEntryPositionsTest {

    private static List<Ast.Entry> entries(String doc) {
        return Parser.parse(doc.getBytes(StandardCharsets.UTF_8)).entries();
    }

    @Test
    void theParserRecordsEachPosition() {
        List<Ast.Entry> es = entries("a   =  1\n\"q\" = 2\nb  {\n  @type =\n    \"x.v1.T\"\n}\n");
        Ast.Assignment a = (Ast.Assignment) es.get(0);
        assertEquals("1:1", a.pos().toString());
        assertEquals("1:5", a.equalsPos().toString());
        assertEquals(4, a.equalsPos().offset());
        assertEquals("2:5", ((Ast.Assignment) es.get(1)).equalsPos().toString());

        Ast.Block b = (Ast.Block) es.get(2);
        assertEquals("3:1", b.pos().toString());
        assertEquals("3:4", b.bracePos().toString());

        Ast.TypeEntry t = (Ast.TypeEntry) b.entries().get(0);
        assertEquals("4:3", t.pos().toString());
        assertEquals("5:5", t.urlPos().toString());
    }

    @Test
    void aValueBlockKeepsItsPositions() {
        Ast.BlockVal v = (Ast.BlockVal) ((Ast.Assignment) entries("m = { k { } x = 1 }").get(0)).value();
        assertEquals("1:9", ((Ast.Block) v.entries().get(0)).bracePos().toString());
        assertEquals("1:15", ((Ast.Assignment) v.entries().get(1)).equalsPos().toString());
    }

    @Test
    void theOlderConstructorsUseTheEntrysPosition() {
        Position p = new Position(3, 7, 20);
        Ast.Value v = new Ast.IntVal(p, "1");
        assertEquals(p, new Ast.Assignment(p, "k", v, List.of(), "").equalsPos());
        assertEquals(p, new Ast.Assignment(p, "k", v, List.of(), "", true).equalsPos());
        assertEquals(p, new Ast.Block(p, "k", List.of(), List.of(), "").bracePos());
        assertEquals(p, new Ast.Block(p, "k", List.of(), List.of(), "", true).bracePos());
        assertEquals(p, new Ast.TypeEntry(p, "x.v1.T", List.of(), "").urlPos());
    }
}
