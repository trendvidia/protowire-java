// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.Ast;
import org.protowire.pxf.Parser;
import org.protowire.pxf.PxfException;
import org.protowire.pxf.android.LiteWireWriter;

/**
 * The lite tier binds {@code @dataset} rows, each to its message's PB bytes
 * (#212). {@code dataset-rows/go.tsv} is protowire-go v1.17.0's bytes for
 * every row of every document of {@code testdata/dataset/} it accepts: each
 * row bound with {@code BindRow} to the type its header names, then
 * marshalled deterministically. {@link GoVerdictsLiteTest} holds the
 * documents Go refuses to Go's error texts. The other tests' bytes and
 * errors are Go's on the same documents.
 */
class LiteDatasetRowsTest {

    private static Path testdata() {
        return Path.of(System.getProperty("protowire.testdata", "testdata-unset"));
    }

    /** Go's rows, by document, in document order: "type hex". */
    private static Map<String, List<String>> goRows() throws IOException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        try (InputStream in = LiteDatasetRowsTest.class.getResourceAsStream("/dataset-rows/go.tsv")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String[] f = line.split("\t", -1);
                out.computeIfAbsent(f[0], k -> new ArrayList<>()).add(f[1] + " " + f[2]);
            }
        }
        return out;
    }

    private static List<String> liteRows(Ast.Document doc) {
        List<String> out = new ArrayList<>();
        for (Ast.DatasetDirective ds : doc.datasets()) {
            Iterator<byte[]> rows = LiteWireWriter.encodeRows(ds, LiteCorpusTest.meta(ds.type()));
            while (rows.hasNext()) out.add(ds.type() + " " + HexFormat.of().formatHex(rows.next()));
        }
        return out;
    }

    @Test
    void eachRowIsGosBytes() throws IOException {
        Map<String, List<String>> go = goRows();
        assertEquals(14, go.size(), "documents with rows");
        int rows = 0;
        for (Map.Entry<String, List<String>> e : go.entrySet()) {
            Ast.Document doc = Parser.parse(Files.readAllBytes(testdata().resolve("dataset").resolve(e.getKey())));
            assertEquals(e.getValue(), liteRows(doc), e.getKey());
            rows += e.getValue().size();
        }
        assertEquals(26, rows);
    }

    private static Ast.DatasetDirective dataset(String doc) {
        return Parser.parse(doc.getBytes(StandardCharsets.UTF_8)).datasets().get(0);
    }

    /** A row the type refuses fails when the iterator reaches it, at the row, naming its column. */
    @Test
    void aBadRowFailsAtItsTurn() {
        Ast.DatasetDirective ds = dataset("@dataset test.v1.AllTypes (string_field, int32_field)\n(\"a\", 1)\n(\"b\", \"x\")\n");
        Iterator<byte[]> rows = LiteWireWriter.encodeRows(ds, LiteCorpusTest.meta("test.v1.AllTypes"));
        assertEquals("0a01611001", HexFormat.of().formatHex(rows.next()));
        PxfException e = assertThrows(PxfException.class, rows::next);
        assertEquals("3:1: column int32_field: expected integer for field \"int32_field\"", e.getMessage());
        assertFalse(rows.hasNext());
    }

    /** A broken column fails the first row, its cells empty or not, and an empty dataset binds nothing. */
    @Test
    void theColumnsAreCheckedAtTheFirstRow() {
        Ast.DatasetDirective ds = dataset("@dataset dataset.v1.Event (id, plain.x)\n(\"a\", )\n");
        PxfException e = assertThrows(PxfException.class,
            () -> LiteWireWriter.encodeRows(ds, LiteCorpusTest.meta("dataset.v1.Event")).next());
        assertEquals("2:1: @dataset column \"plain.x\": \"plain\" is not a message field", e.getMessage());

        e = assertThrows(PxfException.class,
            () -> LiteWireWriter.encodeRow(ds.columns(), ds.rows().get(0), LiteCorpusTest.meta("dataset.v1.Event")));
        assertEquals("2:1: @dataset column \"plain.x\": \"plain\" is not a message field", e.getMessage());

        assertFalse(LiteWireWriter.encodeRows(dataset("@dataset dataset.v1.Event (id, plain.x)\n"),
            LiteCorpusTest.meta("dataset.v1.Event")).hasNext());
    }

    @Test
    void oneRowBindsAlone() {
        Ast.DatasetDirective ds = dataset("@dataset test.v1.AllTypes (string_field, int32_field)\n(\"a\", 1)\n");
        assertEquals("0a01611001",
            HexFormat.of().formatHex(LiteWireWriter.encodeRow(ds.columns(), ds.rows().get(0), LiteCorpusTest.meta("test.v1.AllTypes"))));
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> LiteWireWriter.encodeRow(List.of("string_field"), ds.rows().get(0), LiteCorpusTest.meta("test.v1.AllTypes")))
            .getMessage().contains("1 columns vs 2 cells"));
    }
}
