// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Message;
import dataset.v1.Paths.Event;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.AllTypes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the spec repo's {@code testdata/dataset/} corpus (#99, #101, #102),
 * vendored under {@code src/test/resources/corpus/dataset/} from
 * trendvidia/protowire 234cf29, on every dataset path: {@code Pxf.parse} plus
 * {@code BindRow}, {@code DatasetReader}, and the materializing decoder. Each
 * fixture's leading comment and the corpus README state its verdict; this
 * table is that statement, and {@link #everyFixtureHasAVerdict} fails if a
 * re-vendored fixture has none.
 */
class DatasetCorpusTest {

    /** Rejected by the header or a row's syntax, so a parse with no schema rejects them. */
    private static final Set<String> PARSE_REJECTS = Set.of(
            "arity-long", "arity-short", "with-body", "with-type", "truncated-row-eof",
            "truncated-row-then-rows", "junk-between-rows", "string-newline-in-cell",
            "duplicate-column", "dotted-overlap", "dotted-overlap-deep", "dotted-empty-segment", "dotted-trailing-dot");

    /** Parsed, and rejected when a row binds. */
    private static final Set<String> BIND_REJECTS = Set.of(
            "block-on-scalar", "null-on-scalar-cell", "dotted-oneof-two-members", "dotted-through-repeated",
            "dotted-through-map", "dotted-through-scalar", "dotted-into-timestamp", "dotted-into-wrapper",
            "dotted-into-duration", "dotted-into-bignum", "dotted-unknown-segment", "dotted-required-missing",
            "dotted-bad-header-empty-row");

    private static final Set<String> ACCEPTS = Set.of(
            "empty-rows", "rows-end-at-directive", "list-cell", "block-cell", "nested-cells", "cell-framing",
            "null-on-optional-cell", "dotted-column", "dotted-grouping", "dotted-null-leaf", "dotted-oneof",
            "dotted-required", "dotted-with-value-cells", "dotted-into-any", "dotted-into-secret",
            "dotted-bad-header-no-rows");

    private static String stem(CorpusFixtures.Fixture f) {
        return f.name().substring(0, f.name().length() - ".pxf".length());
    }

    private static Supplier<Message.Builder> rowType(byte[] doc) {
        return new String(doc, StandardCharsets.UTF_8).contains("@dataset dataset.v1.Event")
                ? Event::newBuilder : AllTypes::newBuilder;
    }

    private static List<Message> viaParse(byte[] doc) {
        Supplier<Message.Builder> type = rowType(doc);
        List<Message> out = new ArrayList<>();
        for (Ast.DatasetDirective t : Pxf.parse(doc).datasets()) {
            for (Ast.DatasetRow row : t.rows()) {
                Message.Builder b = type.get();
                BindRow.bindRow(b, t.columns(), row);
                out.add(b.build());
            }
        }
        return out;
    }

    private static List<Message> viaReader(byte[] doc) throws IOException {
        Supplier<Message.Builder> type = rowType(doc);
        List<Message> out = new ArrayList<>();
        InputStream in = new ByteArrayInputStream(doc);
        while (true) {
            DatasetReader r;
            try {
                r = new DatasetReader(in);
            } catch (NoSuchElementException e) {
                if (out.isEmpty() && !hasDataset(doc)) throw e;
                return out; // the input held no further @dataset
            }
            while (true) {
                Message.Builder b = type.get();
                if (!r.scan(b)) break;
                out.add(b.build());
            }
            in = r.tail();
        }
    }

    private static boolean hasDataset(byte[] doc) {
        return new String(doc, StandardCharsets.UTF_8).contains("@dataset");
    }

    @Test
    void everyFixtureHasAVerdict() throws IOException {
        Set<String> all = CorpusFixtures.load("dataset").stream().map(DatasetCorpusTest::stem)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> known = new TreeSet<>(PARSE_REJECTS);
        known.addAll(BIND_REJECTS);
        known.addAll(ACCEPTS);
        assertEquals(known, all);
        assertEquals(42, all.size());
    }

    @Test
    void everyPathMeetsEveryVerdict() throws IOException {
        for (CorpusFixtures.Fixture f : CorpusFixtures.load("dataset")) {
            String name = stem(f);
            if (PARSE_REJECTS.contains(name)) {
                assertThrows(PxfException.class, () -> Pxf.parse(f.data()), name + " (parse)");
                assertThrows(RuntimeException.class, () -> viaReader(f.data()), name + " (reader)");
                assertThrows(PxfException.class,
                        () -> Pxf.unmarshalFull(f.data(), rowType(f.data()).get()), name + " (decoder)");
            } else if (BIND_REJECTS.contains(name)) {
                Pxf.parse(f.data());
                assertThrows(PxfException.class, () -> viaParse(f.data()), name + " (parse + bindRow)");
                assertThrows(PxfException.class, () -> viaReader(f.data()), name + " (reader)");
            } else {
                assertEquals(viaParse(f.data()), viaReader(f.data()), name);
                Pxf.unmarshalFull(f.data(), rowType(f.data()).get());
            }
        }
    }

    private static List<Message> rows(String name) throws IOException {
        for (CorpusFixtures.Fixture f : CorpusFixtures.load("dataset")) {
            if (f.name().equals(name + ".pxf")) return viaReader(f.data());
        }
        throw new AssertionError("no fixture " + name);
    }

    // -- value cells, #99 ----------------------------------------------------

    @Test
    void valueCellsBindAsTheirBodyEntry() throws IOException {
        AllTypes r = (AllTypes) rows("list-cell").get(0);
        assertEquals(List.of("alpha", "beta"), r.getRepeatedStringList());

        r = (AllTypes) rows("block-cell").get(0);
        assertEquals("inner", r.getNestedField().getName());
        assertEquals(99, r.getNestedField().getValue());

        List<Message> nested = rows("nested-cells");
        AllTypes r1 = (AllTypes) nested.get(0);
        assertEquals("n1", r1.getNestedField().getName());
        assertEquals(List.of("x", "y"), r1.getRepeatedNestedList().stream().map(n -> n.getName()).toList());
        assertEquals(Map.of("k", "v"), r1.getStringMapMap());
        assertTrue(((AllTypes) nested.get(1)).hasNestedField(), "{} is a present empty message");
        assertFalse(((AllTypes) nested.get(2)).hasNestedField(), "an empty cell stays absent");
        assertFalse(((AllTypes) nested.get(3)).hasNestedField(), "null leaves the message unset");

        List<Message> framed = rows("cell-framing");
        assertEquals("a), (b,", ((AllTypes) framed.get(0)).getNestedField().getName());
        assertEquals(List.of("),", "(,"), ((AllTypes) framed.get(0)).getRepeatedStringList());
        assertEquals("x) \"y\", (z", ((AllTypes) framed.get(1)).getNestedField().getName());
        assertEquals(List.of("end"), ((AllTypes) framed.get(1)).getRepeatedStringList());
    }

    // -- dotted paths, #101 --------------------------------------------------

    @Test
    void pathsGroupIntoOneBlockPerPrefix() throws IOException {
        List<Message> g = rows("dotted-grouping");
        Event r1 = (Event) g.get(0);
        assertEquals(0.5, r1.getDesc().getEnergy());
        assertEquals(60, r1.getDesc().getTempo().getMinBpm());
        assertEquals(120, r1.getDesc().getTempo().getMaxBpm());
        assertEquals(List.of("x"), r1.getDesc().getTagsList());
        assertFalse(((Event) g.get(1)).getDesc().hasTempo(), "no non-empty cell, no block");
        assertFalse(((Event) g.get(2)).hasDesc(), "no non-empty cell, no sub-message");
        assertEquals(9, ((Event) g.get(3)).getDesc().getTempo().getMaxBpm());

        Event leaf = (Event) rows("dotted-null-leaf").get(0);
        assertTrue(leaf.hasDesc(), "the parent of a null leaf is present");
        assertFalse(leaf.getDesc().hasLabel());

        List<Message> oneof = rows("dotted-oneof");
        assertEquals("x", ((Event) oneof.get(0)).getA().getName());
        assertEquals(1, ((Event) oneof.get(0)).getA().getN());
        assertEquals("y", ((Event) oneof.get(1)).getB().getName());

        List<Message> req = rows("dotted-required");
        assertFalse(((Event) req.get(0)).hasReq(), "an absent parent owes no required field");
        assertEquals(5, ((Event) req.get(1)).getReq().getN());
        assertEquals("dflt", ((Event) req.get(1)).getReq().getD(), "a default applies under a present parent");

        Event cells = (Event) rows("dotted-with-value-cells").get(0);
        assertEquals(60, cells.getDesc().getTempo().getMinBpm());
        assertEquals("p", cells.getPicks(0).getName());

        Event any = (Event) rows("dotted-into-any").get(0);
        assertEquals("type.googleapis.com/dataset.v1.Pick", any.getPayload().getTypeUrl());

        Event secret = (Event) rows("dotted-into-secret").get(0);
        assertEquals("s3cret", secret.getPw().getValue());
        assertEquals("db password", secret.getPw().getHint());

        assertEquals(List.of(), rows("dotted-bad-header-no-rows"));
    }

    // -- errors say where --------------------------------------------------

    @Test
    void aRowErrorIsAtTheRowAndNamesTheColumn() {
        byte[] doc = "# c\n@dataset test.v1.AllTypes (string_field, int32_field)\n\n(\"a\", 1)\n(\"b\", \"x\")\n".getBytes(StandardCharsets.UTF_8);
        PxfException e = assertThrows(PxfException.class, () -> viaReader(doc));
        assertEquals(5, e.position().line(), e.getMessage());
        assertEquals(1, e.position().column(), e.getMessage());
        assertTrue(e.getMessage().contains("column int32_field: "), e.getMessage());

        PxfException p = assertThrows(PxfException.class, () -> viaParse(doc));
        assertEquals(e.getMessage(), p.getMessage(), "the two paths report the same error");
    }

    @Test
    void aRawNewlineInACellIsReportedAtItsLine() {
        byte[] doc = "@dataset test.v1.AllTypes (string_field, int32_field)\n\n(\"row\n-1\", 1)\n".getBytes(StandardCharsets.UTF_8);
        PxfException e = assertThrows(PxfException.class, () -> viaReader(doc));
        assertEquals(3, e.position().line(), e.getMessage());
    }

    @Test
    void aMissingRequiredFieldNamesNoColumn() throws IOException {
        CorpusFixtures.Fixture f = CorpusFixtures.load("dataset").stream()
                .filter(x -> x.name().equals("dotted-required-missing.pxf")).findFirst().orElseThrow();
        PxfException e = assertThrows(PxfException.class, () -> viaReader(f.data()));
        assertTrue(e.getMessage().contains("required field \"req.n\" is absent"), e.getMessage());
        assertFalse(e.getMessage().contains("column "), e.getMessage());
    }
}
