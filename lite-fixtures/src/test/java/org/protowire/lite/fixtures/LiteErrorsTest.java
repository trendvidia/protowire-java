// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import liteerrors.v1.HolderPxfMeta;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.Parser;
import org.protowire.pxf.PxfException;
import org.protowire.pxf.android.LiteWireWriter;

/**
 * A document the lite tier refuses gets a positioned {@link PxfException}
 * with protowire-go's text, never an {@code IllegalArgumentException} (#191).
 * These are the errors the spec corpus does not reach, on
 * {@code lite_errors.proto}; {@link GoVerdictsLiteTest} holds the corpus to
 * Go's table. Each expected text is protowire-go v1.17.0's {@code UnmarshalFull}
 * on the same schema.
 */
class LiteErrorsTest {

    private static final Map<String, String> GO = new LinkedHashMap<>();
    static {
        // A required field is named by its path, as Go names it.
        GO.put("one { id = \"x\" }", "1:1: required field \"one.name\" is absent");
        GO.put("items = [{ name = \"a\" }, { id = \"b\" }]", "1:1: required field \"items[1].name\" is absent");
        GO.put("keyed { \"k1\" { name = \"a\" } \"k2\" { } }", "1:1: required field \"keyed[1].name\" is absent");
        GO.put("by_key = { a: { name = \"n\" } b: { id = \"z\" } }",
            "1:1: required field \"by_key[\\\"b\\\"].name\" is absent");
        GO.put("by_num = { 7: { } }", "1:1: required field \"by_num[7].name\" is absent");
        // The schema refuses the entry.
        GO.put("nope = 1", "1:1: unknown field \"nope\" in liteerrors.v1.Holder");
        GO.put("s { }", "1:1: field \"s\" is not a message type, cannot use block syntax");
        GO.put("one { x: 1 }", "1:7: unexpected ':' in message context, use '=' for field assignments");
        // A value of the wrong kind, or out of range.
        GO.put("s = 1", "1:5: expected string for field \"s\"");
        GO.put("i32 = \"x\"", "1:7: expected integer for field \"i32\"");
        GO.put("i32 = 3000000000", "1:7: invalid int32: 3000000000");
        GO.put("u64 = -1", "1:7: invalid uint64: -1");
        GO.put("d = true", "1:5: expected number for field \"d\"");
        GO.put("flag = 1", "1:8: expected bool for field \"flag\"");
        GO.put("raw = \"abc\"", "1:7: expected bytes for field \"raw\"");
        GO.put("color = COLOR_BLUE", "1:9: unknown enum value \"COLOR_BLUE\" for liteerrors.v1.Color");
        GO.put("color = 99999999999", "1:9: invalid enum number: 99999999999");
        GO.put("color = \"x\"", "1:9: expected enum name or number for field \"color\"");
        GO.put("one = 5", "1:7: expected '{' for message field \"one\", got integer (\"5\")");
        GO.put("nums = [1, \"x\"]", "1:12: expected integer for field \"nums\"");
        GO.put("counts = { a: \"x\" }", "1:15: expected integer for field \"counts\"");
    }

    private static void encode(String doc) {
        LiteWireWriter.encode(Parser.parse(doc.getBytes(StandardCharsets.UTF_8)), HolderPxfMeta.INSTANCE);
    }

    @Test
    void eachRefusalIsGosError() {
        GO.forEach((doc, want) -> assertEquals(want, assertThrows(PxfException.class, () -> encode(doc)).getMessage(), doc));
    }

    /** An Any body's fields are named under the Any's path, as Go names them (#213). */
    @Test
    void aRequiredFieldInAnAnyBodyIsNamedByItsPath() {
        org.protowire.pxf.SimplePxfRegistry types = new org.protowire.pxf.SimplePxfRegistry();
        types.register(liteerrors.v1.ItemPxfMeta.INSTANCE);
        PxfException e = assertThrows(PxfException.class, () -> LiteWireWriter.encode(
            Parser.parse("payload { @type = \"liteerrors.v1.Item\" id = \"x\" }"), HolderPxfMeta.INSTANCE, types));
        assertEquals("1:1: required field \"payload.name\" is absent", e.getMessage());
    }

    @Test
    void anAssignmentInAMapIsReportedAtTheEntry() {
        // Go says 1:14, at the '='. The AST keeps no position for it, so the
        // lite tier reports the entry; the text is Go's.
        assertEquals("1:12: unexpected '=' in map, use ':' for map entries",
            assertThrows(PxfException.class, () -> encode("counts = { a = 1 }")).getMessage());
    }
}
