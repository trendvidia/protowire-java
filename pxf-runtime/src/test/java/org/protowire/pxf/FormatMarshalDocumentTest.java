// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Format#marshalDocument} lays a document out as protowire-go's
 * Marshal writes a message; {@link Format#formatDocument} as its
 * FormatDocument does (#187, #186, #203).
 */
class FormatMarshalDocumentTest {

    private static final Position P = Position.UNKNOWN;

    private static Ast.Assignment field(String name, Ast.Value v) {
        return new Ast.Assignment(P, name, v, List.of(), "");
    }

    private static Ast.Document doc(Ast.Entry... entries) {
        return Ast.Document.of("", List.of(entries));
    }

    @Test
    void anEmptyListOrMapFieldIsWrittenOnItsLine() {
        Ast.Document d = doc(field("rl", new Ast.ListVal(P, List.of())), field("rm", new Ast.BlockVal(P, List.of())));
        assertEquals("rl = []\nrm = {}\n", Format.marshalDocument(d));
        // FormatDocument keeps protowire-go's layout of the same document.
        assertEquals("rl = [\n]\nrm = {\n}\n", Format.formatDocument(d));
    }

    @Test
    void anEmptyElementOrMapValueIsStillABlock() {
        Ast.Document d = doc(
                field("l", new Ast.ListVal(P, List.of(new Ast.BlockVal(P, List.of())))),
                field("m", new Ast.BlockVal(P, List.of(new Ast.MapEntry(P, "k", new Ast.BlockVal(P, List.of()), List.of(), "")))));
        assertEquals(Format.formatDocument(d), Format.marshalDocument(d));
    }

    @Test
    void aBigFloatThatRoundsPastTheBoundIsNotWritten() {
        // pb/bigfloat-rounds-past-bound.binpb as the lite tier reads it.
        Ast.Document d = doc(field("big_float", new Ast.FloatVal(P, "1.2e+4932", "3")));
        PxfException e = assertThrows(PxfException.class, () -> Format.marshalDocument(d));
        assertTrue(e.getMessage().startsWith("1:1: pxf: big float literal 1.2e+4932 has binary exponent 16385"), e.getMessage());
        assertEquals("big_float = 1.2e+4932p3\n", Format.formatDocument(d));
    }

    @Test
    void aBodyPastMaxNestingDepthIsNotWritten() {
        Ast.Value v = new Ast.ListVal(P, List.of());
        for (int i = 0; i < Limits.MAX_NESTING_DEPTH; i++) v = new Ast.BlockVal(P, List.of(field("x", v)));
        Ast.Document d = doc(field("x", v));
        PxfException e = assertThrows(PxfException.class, () -> Format.marshalDocument(d));
        assertEquals("1:1: pxf: nesting depth exceeds MaxNestingDepth=100", e.getMessage());
    }
}
