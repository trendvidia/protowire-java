// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.MapKeys;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A map key binds exactly the spellings the grammar admits (draft -01
 * §entries-and-keys, {@code map-key = identifier / string / integer /
 * bool}; protowire#284, #76). Mirrors protowire-go's
 * {@code bool_map_key_test.go}: a bool key is the keyword {@code true} /
 * {@code false} bare, the integers {@code 1} / {@code 0}, or the quoted
 * literals {@code "true"} / {@code "false"} — and nothing
 * {@code Boolean.parseBoolean} would have taken on top, which used to bind
 * silently as {@code false}.
 */
class BoolMapKeyTest {

    private static MapKeys decode(String doc) {
        MapKeys.Builder b = MapKeys.newBuilder();
        Pxf.unmarshal(doc.getBytes(StandardCharsets.UTF_8), b);
        return b.build();
    }

    private static PxfException reject(String doc, String needle) {
        PxfException e = assertThrows(PxfException.class, () -> decode(doc), doc);
        assertTrue(e.getMessage().contains(needle), doc + " -> " + e.getMessage());
        return e;
    }

    // Every spelling Boolean.parseBoolean / strconv.ParseBool accepts, plus
    // the two words; tried bare and quoted, since the split between the two
    // is what makes this non-obvious.
    private static final List<String> SPELLINGS =
            List.of("1", "t", "T", "TRUE", "true", "True", "0", "f", "F", "FALSE", "false", "False");
    private static final Map<String, Boolean> BARE = Map.of("1", true, "0", false, "true", true, "false", false);
    private static final Map<String, Boolean> QUOTED = Map.of("true", true, "false", false);

    @Test
    void bareSpellings() {
        for (String sp : SPELLINGS) {
            String doc = "by_bool = { " + sp + ": \"v\" }";
            if (BARE.containsKey(sp)) {
                assertEquals("v", decode(doc).getByBoolMap().get(BARE.get(sp)), doc);
            } else {
                // An identifier key on a bool K matches nothing: an
                // identifier names a field, and a map has none.
                reject(doc, "invalid bool map key " + sp + " for field \"by_bool\": a bool key is true, false, 0, 1, \"true\" or \"false\"");
            }
        }
    }

    @Test
    void quotedSpellings() {
        for (String sp : SPELLINGS) {
            String doc = "by_bool = { \"" + sp + "\": \"v\" }";
            if (QUOTED.containsKey(sp)) {
                assertEquals("v", decode(doc).getByBoolMap().get(QUOTED.get(sp)), doc);
            } else {
                // Includes "1" and "0": an integer literal inside a string
                // is not a bool literal.
                reject(doc, "invalid bool map key \"" + sp + "\" for field \"by_bool\"");
            }
        }
    }

    // All three admitted spellings of each value land on the same key.
    @Test
    void spellingIsNotPartOfTheValue() {
        MapKeys m = decode("by_bool = {\n  1: \"a\"\n  \"false\": \"b\"\n  true: \"c\"\n  false: \"d\"\n}");
        assertEquals(2, m.getByBoolCount());
        assertEquals("c", m.getByBoolMap().get(true), "the later spelling wins, as any duplicate key does");
        assertEquals("d", m.getByBoolMap().get(false));
    }

    // The keyword is a key on a map<bool,V> field and nothing else. On a
    // string K it is rejected naming the key — the string "true" is
    // spelled quoted — and on an integer K it fails the way an identifier
    // does.
    @Test
    void keywordOnOtherKeyTypes() {
        for (String kw : List.of("true", "false")) {
            PxfException e = reject("by_string = { " + kw + ": \"v\" }", "invalid string map key " + kw + " for field \"by_string\"");
            assertTrue(e.getMessage().contains("write \"" + kw + "\" for the string"), e.getMessage());
        }
        assertEquals("v", decode("by_string = { \"true\": \"v\" }").getByStringMap().get("true"));
        reject("by_int32 = { true: \"v\" }", "invalid int32 map key: true");
        reject("by_int32 = { yes: \"v\" }", "invalid int32 map key: yes");
    }

    // Integral keys bind to the field's width and signedness, whichever
    // token carried the text: a bare integer or a quoted one.
    @Test
    void integralKeysBindToTheirWidth() {
        MapKeys m = decode("""
                by_int32 = { -5: "a" }
                by_uint32 = { 4294967295: "b" }
                by_int64 = { -9223372036854775808: "c" }
                by_uint64 = { 18446744073709551615: "d" }
                by_sint32 = { "-7": "e" }
                by_fixed64 = { 7: "f" }
                """);
        assertEquals("a", m.getByInt32Map().get(-5));
        assertEquals("b", m.getByUint32Map().get(-1));            // 4294967295 as a Java int
        assertEquals("c", m.getByInt64Map().get(Long.MIN_VALUE));
        assertEquals("d", m.getByUint64Map().get(-1L));           // 2^64-1 as a Java long
        assertEquals("e", m.getBySint32Map().get(-7));
        assertEquals("f", m.getByFixed64Map().get(7L));
        reject("by_uint32 = { 4294967296: \"x\" }", "invalid uint32 map key: 4294967296");
        reject("by_uint32 = { -1: \"x\" }", "invalid uint32 map key: -1");
        reject("by_int32 = { 2147483648: \"x\" }", "invalid int32 map key: 2147483648");
    }
}
