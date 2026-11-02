// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;
import textshape.v1.Item;
import textshape.v1.Shapes;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The text of each message and map shape. The lite tier's TextShapeTest pins
 * the same text, so the two tiers cannot drift apart (#141).
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
        assertEquals(TEXT, new String(Pxf.marshal(shapes()), StandardCharsets.UTF_8));
    }
}
