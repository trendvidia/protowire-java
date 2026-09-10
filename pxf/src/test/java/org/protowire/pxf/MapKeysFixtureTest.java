// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import mapkeys.v1.BoolKeys.Flags;
import mapkeys.v1.BoolKeys.Labels;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the spec repo's {@code testdata/map-keys/} fixtures (vendored
 * under {@code src/test/resources/map-keys/} from trendvidia/protowire
 * a0dd520, 2026-09-08, the {@code fmt-dotted-keys} pair from its branch
 * {@code map-keys-dotted-313} at f7d467f; the README there states the
 * verdicts). Bool keys (#76): three documents MUST bind to the keys true
 * and false; every file under {@code invalid/} MUST NOT bind, with an
 * error naming the key. Key spelling (#82, #83): each {@code fmt-*} input
 * formats to exactly its {@code .expected.pxf}, the expected file is a
 * fixed point, both bind, and the marshaller writes the expected
 * spellings.
 */
class MapKeysFixtureTest {

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = MapKeysFixtureTest.class.getResourceAsStream("/map-keys/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return in.readAllBytes();
        }
    }

    private static Flags decode(String name) throws IOException {
        Flags.Builder b = Flags.newBuilder();
        Pxf.unmarshal(fixture(name), b);
        return b.build();
    }

    @Test
    void mustBind() throws IOException {
        for (String name : List.of("bool-keys.pxf", "bool-keys-keyword.pxf", "bool-keys-integer.pxf")) {
            Flags f = decode(name);
            assertEquals(2, f.getByFlagCount(), name);
            assertTrue(f.getByFlagMap().containsKey(true), name);
            assertTrue(f.getByFlagMap().containsKey(false), name);
        }
    }

    // The invalid/ directory, by name: each carries the offending key in
    // its file name after the last '-'.
    private static final List<String> INVALID = List.of(
            "identifier-lower-f", "identifier-lower-t", "identifier-title-False", "identifier-title-True",
            "identifier-upper-F", "identifier-upper-FALSE", "identifier-upper-T", "identifier-upper-TRUE",
            "identifier-word-yes", "string-lower-t", "string-one-1", "string-upper-TRUE", "string-zero-0");

    @Test
    void mustNotBind() {
        for (String stem : INVALID) {
            String key = stem.substring(stem.lastIndexOf('-') + 1);
            PxfException e = assertThrows(PxfException.class, () -> decode("invalid/" + stem + ".pxf"), stem);
            assertTrue(e.getMessage().contains("invalid bool map key"), stem + " -> " + e.getMessage());
            assertTrue(e.getMessage().contains(key), stem + " must name the key: " + e.getMessage());
            assertTrue(e.getMessage().contains("\"by_flag\""), stem + " must name the field: " + e.getMessage());
        }
    }

    // -- fmt pairs: the document's spelling survives where it matters ------

    private static String text(String name) throws IOException {
        return new String(fixture(name), StandardCharsets.UTF_8);
    }

    private static void assertFormatsTo(String pair) throws IOException {
        String expected = text(pair + ".expected.pxf");
        assertEquals(expected, Format.formatDocument(Parser.parse(text(pair + ".pxf"))), pair + ": input → expected");
        assertEquals(expected, Format.formatDocument(Parser.parse(expected)), pair + ": expected is a fixed point");
    }

    private static Labels labels(String name) throws IOException {
        Labels.Builder b = Labels.newBuilder();
        Pxf.unmarshal(fixture(name), b);
        return b.build();
    }

    // On a string-keyed map: "true", "false", "null" and "123" stay
    // quoted (bare they would be a bool, a non-key, an integer); the quoted
    // identifier-safe "plain" canonicalizes to bare; bare stays bare.
    @Test
    void keywordKeysPair() throws IOException {
        assertFormatsTo("fmt-keyword-keys");
        for (String name : List.of("fmt-keyword-keys.pxf", "fmt-keyword-keys.expected.pxf")) {
            Map<String, String> m = labels(name).getByLabelMap();
            assertEquals(6, m.size(), name);
            assertEquals("quoted keyword", m.get("true"), name);
            assertEquals("quoted integer", m.get("123"), name);
            assertEquals("bare identifier", m.get("bare"), name);
        }
    }

    // On a bool-keyed map: a bare true and a bare 0 stay bare — a
    // formatter does not add quotes the author did not write (and every
    // formatter in the family used to quote the integer).
    @Test
    void bareKeysPair() throws IOException {
        assertFormatsTo("fmt-bare-keys");
        Flags f = decode("fmt-bare-keys.pxf");
        assertEquals("keyword", f.getByFlagMap().get(true));
        assertEquals("integer", f.getByFlagMap().get(false));
    }

    // The marshaller side writes the same spellings the expected files
    // pin: a string key unquoted iff identifier-safe and not a keyword.
    @Test
    void marshallerSpellsStringKeysLikeTheExpectedFile() throws IOException {
        Labels msg = labels("fmt-keyword-keys.expected.pxf");
        String out = new String(Pxf.marshal(msg), StandardCharsets.UTF_8);
        for (String line : List.of("\"true\": \"quoted keyword\"", "\"false\": \"quoted keyword\"",
                "\"null\": \"quoted keyword\"", "\"123\": \"quoted integer\"",
                "plain: \"quoted identifier-safe key\"", "bare: \"bare identifier\"")) {
            assertTrue(out.contains(line), line + " not in:\n" + out);
        }
        // And what it writes reads back to the same keys.
        Labels back = Labels.newBuilder().mergeFrom(labels("fmt-keyword-keys.expected.pxf")).build();
        Labels.Builder b = Labels.newBuilder();
        Pxf.unmarshal(out.getBytes(StandardCharsets.UTF_8), b);
        assertEquals(back.getByLabelMap(), b.getByLabelMap());
    }

    // A dotted string key is identifier-safe (the identifier production
    // admits "." in ident-part), so the marshaller writes it bare and fmt
    // unquotes a quoted one; keys that fail ident-start (".e", "1.5") stay
    // quoted (protowire#313, #83).
    @Test
    void dottedKeysPair() throws IOException {
        assertFormatsTo("fmt-dotted-keys");
        for (String name : List.of("fmt-dotted-keys.pxf", "fmt-dotted-keys.expected.pxf")) {
            Map<String, String> m = labels(name).getByLabelMap();
            assertEquals(Map.of("a.b", "quoted dotted", "c.d", "bare dotted", ".e", "leading dot", "1.5", "float-shaped"), m, name);
        }
        String out = new String(Pxf.marshal(labels("fmt-dotted-keys.expected.pxf")), StandardCharsets.UTF_8);
        for (String line : List.of("a.b: \"quoted dotted\"", "c.d: \"bare dotted\"", "\".e\": \"leading dot\"", "\"1.5\": \"float-shaped\"")) {
            assertTrue(out.contains(line), line + " not in:\n" + out);
        }
    }
}
