// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.fixtures;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import keyed.v1.Node;
import keyed.v1.NodePxfMeta;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.Parser;
import org.protowire.pxf.PxfException;
import org.protowire.pxf.android.LitePxf;

/**
 * What the lite tier writes, its own reader reads back (#203). A message
 * nested through a repeated field takes two levels of text per level, the
 * list or keyed block and the element's body, which the parser counts
 * against MaxNestingDepth. So 50 levels are written and read back, and 51
 * are refused when written, as protowire-go's Marshal refuses them.
 */
class LiteMarshalReadsBackTest {

    /** A node with {@code levels} generations of children below it. */
    private static Node chain(int levels) {
        Node n = Node.newBuilder().setId("leaf").build();
        for (int i = 0; i < levels; i++) {
            n = Node.newBuilder().setId("n" + i).addChildren(n).build();
        }
        return n;
    }

    @Test
    void fiftyLevelsAreWrittenAndReadBack() {
        String text = LitePxf.marshal(chain(50), NodePxfMeta.INSTANCE);
        assertDoesNotThrow(() -> Parser.parse(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** An unset required field with presence has no text, as protowire-go's Marshal says (#187). */
    @Test
    void anUnsetRequiredFieldWithPresenceIsRefused() {
        PxfException e = assertThrows(PxfException.class,
            () -> LitePxf.marshal(nulls.v1.Required.getDefaultInstance(), nulls.v1.RequiredPxfMeta.INSTANCE));
        assertEquals("1:1: pxf: nulls.v1.Required.r is unset, and the field is (pxf.required)", e.getMessage());
    }

    @Test
    void fiftyOneLevelsAreRefused() {
        PxfException e = assertThrows(PxfException.class, () -> LitePxf.marshal(chain(51), NodePxfMeta.INSTANCE));
        assertEquals("1:1: pxf: nesting depth exceeds MaxNestingDepth=100", e.getMessage());
    }
}
