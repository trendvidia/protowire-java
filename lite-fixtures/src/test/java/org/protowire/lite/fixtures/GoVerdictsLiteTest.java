// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.protowire.pxf.Parser;
import org.protowire.pxf.PxfException;
import org.protowire.pxf.PxfMeta;
import org.protowire.pxf.SimplePxfRegistry;
import org.protowire.pxf.android.LiteWireWriter;

/**
 * Every document of the spec corpus gets protowire-go's verdict from the lite
 * tier: accepted where Go accepts it, and otherwise refused with Go's error,
 * its text and position. The verdicts are {@code scripts/go-verdicts/corpus.tsv},
 * written by protowire-go itself, which pxf's {@code GoVerdictsTest} holds the
 * full tier to. The lite tier parses before it binds, as Go's {@code Parse}
 * does, so a document {@code Parse} refuses is held to that error, and the
 * rest to {@code UnmarshalFull}'s.
 */
class GoVerdictsLiteTest {

    /**
     * Documents this tier does not give Go's verdict yet, against the open
     * issue for each. A listed document that does fails the test, so the
     * list only shrinks.
     */
    private static final Map<String, String> KNOWN_GAPS = Map.ofEntries(
        // null on a (pxf.required) field is accepted
        Map.entry("nulls/err-required-optional.pxf", "#189"),
        Map.entry("nulls/err-required-wrapper.pxf", "#189"),
        // refused with IllegalArgumentException, not Go's PXF error
        Map.entry("annotations/missing-required.pxf", "#191"),
        Map.entry("nulls/err-list-element.pxf", "#191"),
        // an Any block's @type entry is refused
        Map.entry("any/assignment.pxf", "#213"),
        Map.entry("any/block.pxf", "#213"),
        Map.entry("any/list-element.pxf", "#213"),
        Map.entry("any/map-value.pxf", "#213"),
        Map.entry("any/url-verbatim.pxf", "#213"),
        Map.entry("any/err-not-any.pxf", "#213"),
        Map.entry("any/err-unresolved.pxf", "#213"),
        // a scalar or an assigned block on a repeated field is accepted
        Map.entry("repeated/err-scalar-entries.pxf", "#214"),
        Map.entry("repeated/err-assigned-block.pxf", "#214"));

    private static final SimplePxfRegistry ANY_TYPES = new SimplePxfRegistry();
    static {
        ANY_TYPES.register(LiteCorpusTest.meta("any.v1.Leaf"));
    }

    record Row(String doc, String type, String decode, String parse) {
        /** Go's verdict for a tier that parses before it binds. */
        String expected() { return parse.equals("ok") ? decode : parse; }
    }

    private static List<Row> table() throws IOException {
        Path p = Path.of(System.getProperty("protowire.goVerdicts", "goVerdicts-unset"));
        assertTrue(Files.isRegularFile(p), "missing " + p + " (run through Gradle)");
        List<Row> rows = new ArrayList<>();
        for (String line : Files.readAllLines(p)) {
            if (line.isEmpty()) continue;
            String[] c = line.split("\t", -1);
            assertEquals(4, c.length, line);
            rows.add(new Row(c[0], c[1], c[2], c[3]));
        }
        return rows;
    }

    /** What this tier says of a document: "ok", or the error as Go would print it. */
    private static String verdict(byte[] doc, String type, boolean any) {
        try {
            PxfMeta m = LiteCorpusTest.meta(type);
            if (any) {
                LiteWireWriter.encode(Parser.parse(doc), m, ANY_TYPES);
            } else {
                LiteWireWriter.encode(Parser.parse(doc), m);
            }
            return "ok";
        } catch (PxfException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage(); // never Go's text
        }
    }

    @TestFactory
    List<DynamicTest> everyDocumentGetsGosVerdict() throws IOException {
        Path root = Path.of(System.getProperty("protowire.testdata", "testdata-unset"));
        List<DynamicTest> tests = new ArrayList<>();
        for (Row r : table()) {
            tests.add(DynamicTest.dynamicTest(r.doc(), () -> {
                String got = verdict(Files.readAllBytes(root.resolve(r.doc())), r.type(), r.doc().startsWith("any/"));
                String issue = KNOWN_GAPS.get(r.doc());
                if (issue == null) {
                    assertEquals(r.expected(), got, r.doc());
                } else {
                    assertNotEquals(r.expected(), got,
                        r.doc() + " gets Go's verdict now: drop it from KNOWN_GAPS (" + issue + ")");
                }
            }));
        }
        return tests;
    }

    @Test
    void knownGapsNameDocumentsInTheTable() throws IOException {
        List<String> docs = table().stream().map(Row::doc).toList();
        KNOWN_GAPS.keySet().forEach(k -> assertTrue(docs.contains(k), k));
    }
}
