// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.fixtures;

import org.junit.jupiter.api.Test;
import org.protowire.pxf.Format;
import org.protowire.pxf.Parser;
import org.protowire.pxf.SimplePxfRegistry;
import org.protowire.pxf.android.LiteWireReader;
import org.protowire.pxf.android.LiteWireWriter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lite tier's half of #168 (trendvidia/protowire#501, draft -01 § Any
 * Blocks): an Any is written as a type entry and body only when reading the
 * body gives back its value, byte for byte. The lite reader writes every Any
 * as its {@code type_url} and raw {@code value}, which the rule allows, so
 * each value below must come back unchanged through text, even the ones whose
 * body would not: unknown fields, fields out of order, explicit zeros, bytes
 * that do not parse, and a chain of Anys past MaxNestingDepth. pxf's
 * {@code AnyBodyReadBackTest} holds the full tier, which does write bodies,
 * to the same cases.
 */
class LiteAnyReadBackTest {

    private static final SimplePxfRegistry LEAF = new SimplePxfRegistry();
    static {
        LEAF.register(LiteCorpusTest.meta("any.v1.Leaf"));
    }

    private static byte[] varint(long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        while ((v & ~0x7fL) != 0) {
            o.write((int) (v & 0x7f) | 0x80);
            v >>>= 7;
        }
        o.write((int) v);
        return o.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    private static byte[] vf(int num, long v) { return cat(varint((long) num << 3), varint(v)); }

    private static byte[] lf(int num, byte[] body) { return cat(varint(((long) num << 3) | 2), varint(body.length), body); }

    private static byte[] str(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    /** {@code google.protobuf.Any{type_url, value}} as protobuf writes it: an empty value is left out. */
    private static byte[] any(String url, byte[] value) {
        return value.length == 0 ? lf(1, str(url)) : cat(lf(1, str(url)), lf(2, value));
    }

    /** {@code any.v1.Leaf} values, as the full tier's cases, by what makes each one's body differ. */
    static Map<String, byte[]> leafValues() {
        byte[] tagT = lf(1, str("t"));
        byte[] n1 = vf(2, 1);
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("canonical", cat(tagT, n1));
        m.put("empty", new byte[0]);
        m.put("unknown field", cat(tagT, vf(101, 1)));
        m.put("fields out of order", cat(n1, tagT));
        m.put("explicit zero", vf(2, 0));
        m.put("long varint", new byte[] {0x10, (byte) 0x81, 0x00});
        m.put("repeated singular field", cat(vf(2, 1), vf(2, 2)));
        m.put("unparsable", new byte[] {(byte) 0xff});
        return m;
    }

    /** Wire to text with the lite reader, text to wire with the lite writer, each with and without the registry. */
    private static void assertReadsBack(String name, byte[] wire) {
        for (SimplePxfRegistry registry : new SimplePxfRegistry[] {null, LEAF}) {
            String text = Format.marshalDocument(registry == null
                    ? LiteWireReader.toAst(wire, LiteCorpusTest.meta("any.v1.Holder"))
                    : LiteWireReader.toAst(wire, LiteCorpusTest.meta("any.v1.Holder"), registry));
            assertTrue(text.contains("type_url"), name + ":\n" + text);
            assertFalse(text.contains("@type"), name + ":\n" + text);
            byte[] back = registry == null
                    ? LiteWireWriter.encode(Parser.parse(text), LiteCorpusTest.meta("any.v1.Holder"))
                    : LiteWireWriter.encode(Parser.parse(text), LiteCorpusTest.meta("any.v1.Holder"), registry);
            assertArrayEquals(wire, back, name + " (registry " + (registry != null) + "): " + HexFormat.of().formatHex(back)
                    + "\n" + text);
        }
    }

    @Test
    void eachValueReadsBackInEachPlaceAnAnyGoes() {
        leafValues().forEach((name, value) -> {
            byte[] a = any("type.googleapis.com/any.v1.Leaf", value);
            assertReadsBack(name + " as payload", lf(1, a));
            assertReadsBack(name + " in a list", cat(lf(2, a), lf(2, a)));
            assertReadsBack(name + " as a map value", lf(3, cat(lf(1, str("k")), lf(2, a))));
        });
        assertReadsBack("unresolved type", lf(1, any("type.googleapis.com/any.v1.Nope", lf(1, str("t")))));
    }

    /** Each value is a Holder whose payload is the next: 120 levels, past MaxNestingDepth, read as one value's bytes. */
    @Test
    void aChainPastTheDepthLimitReadsBack() {
        byte[] value = lf(1, str("t")); // the innermost Leaf
        String url = "type.googleapis.com/any.v1.Leaf";
        for (int i = 0; i < 120; i++) {
            value = lf(1, any(url, value));
            url = "type.googleapis.com/any.v1.Holder";
        }
        assertReadsBack("chain", lf(1, any(url, value)));
    }
}
