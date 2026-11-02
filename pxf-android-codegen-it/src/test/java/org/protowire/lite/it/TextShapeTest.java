// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.it;

import org.junit.jupiter.api.Test;
import org.protowire.pxf.Format;
import org.protowire.pxf.android.LiteWireReader;
import textshape.v1.Item;
import textshape.v1.Shapes;
import textshape.v1.ShapesPxfCodec;
import textshape.v1.ShapesPxfMeta;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The text of each message and map shape, the same text :pxf's TextShapeTest
 * pins for the full tier (#141).
 */
final class TextShapeTest {
    private static final String TEXT = String.join("\n",
        "one {",
        "  name = \"a\"",
        "}",
        "by_name = {",
        "  \"a b\": {",
        "    name = \"q\"",
        "  }",
        "  x: {",
        "    name = \"x\"",
        "  }",
        "  y: {",
        "  }",
        "}",
        "by_num = {",
        "  10: \"ten\"",
        "  9: \"nine\"",
        "}",
        "list = [",
        "  {",
        "    name = \"l\"",
        "  }",
        "]",
        "");

    /** Map entries go in out of order, to show that the text sorts them. */
    private static Shapes shapes() {
        return Shapes.newBuilder()
            .setOne(Item.newBuilder().setName("a"))
            .putByName("y", Item.getDefaultInstance())
            .putByName("x", Item.newBuilder().setName("x").build())
            .putByName("a b", Item.newBuilder().setName("q").build())
            .putByNum(9, "nine")
            .putByNum(10, "ten")
            .addList(Item.newBuilder().setName("l"))
            .build();
    }

    @Test
    void marshalWritesEachShape() {
        assertEquals(TEXT, ShapesPxfCodec.marshal(shapes()));
    }

    @Test
    void textReadsBack() {
        assertEquals(shapes(), ShapesPxfCodec.unmarshal(TEXT));
    }

    // Protobuf keeps the last of a key repeated on the wire, and the text can't
    // hold both: a duplicate key is an error when it's read back.
    @Test
    void lastOfARepeatedWireKeyWins() {
        byte[] wire = {0x1a, 0x07, 0x08, 0x01, 0x12, 0x03, 'o', 'l', 'd',
                       0x1a, 0x07, 0x08, 0x01, 0x12, 0x03, 'n', 'e', 'w'};
        assertEquals("by_num = {\n  1: \"new\"\n}\n",
            Format.formatDocument(LiteWireReader.toAst(wire, ShapesPxfMeta.INSTANCE)));
    }
}
