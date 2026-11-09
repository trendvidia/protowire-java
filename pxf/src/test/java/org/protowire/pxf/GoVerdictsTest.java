// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every document of the spec corpus gets protowire-go's verdict from
 * {@code unmarshalFull}: accepted where Go accepts it, and otherwise refused
 * with Go's error, its text and position. The verdicts are
 * {@code scripts/go-verdicts/corpus.tsv}, written by protowire-go itself
 * ({@code scripts/go-verdicts/main.go}); the conformance job reruns that
 * against protowire-go main, so the table cannot fall behind Go.
 * lite-fixtures' {@code GoVerdictsLiteTest} holds the lite tier to the same
 * table.
 */
class GoVerdictsTest {

    /**
     * Documents this tier does not give Go's verdict yet, against the open
     * issue for each. A listed document that does fails the test, so the
     * list only shrinks.
     */
    private static final Map<String, String> KNOWN_GAPS = Map.of(
            // protobuf-java 3.25 reads no editions features: the EXPLICIT
            // scalar has no presence, so its null is refused
            "nulls/editions-explicit.pxf", "#190");

    record Row(String doc, String type, String decode, String parse) {}

    static List<Row> table() throws IOException {
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

    private static Map<String, Descriptor> types() throws IOException {
        Map<String, Descriptor> types = new HashMap<>();
        List<FileDescriptor> files = new ArrayList<>(List.of(
                annotationsFile(), any.v1.Any.getDescriptor(), bf.v1.Bigfloat.getDescriptor(),
                blocks.v1.Blocks.getDescriptor(), dup.v1.Duplicates.getDescriptor(),
                duration.v1.Duration.getDescriptor(), keyed.v1.Keyed.getDescriptor(),
                mapkeys.v1.BoolKeys.getDescriptor(), nulls.v1.Nulls.getDescriptor(),
                nulls.v1.NullsProto2.getDescriptor(), strings.v1.Strings.getDescriptor(),
                org.protowire.pxf.testproto.AllTypes.getDescriptor().getFile()));
        // nulls_editions.proto needs protoc's editions flag, so its
        // descriptor is checked in (RejectRulesCorpusTest.nullsEditions).
        try (InputStream in = GoVerdictsTest.class.getResourceAsStream("/editions/nulls_editions.binpb")) {
            assertNotNull(in, "missing nulls_editions.binpb");
            files.add(FileDescriptor.buildFrom(FileDescriptorSet.parseFrom(in).getFile(0), new FileDescriptor[0]));
        } catch (com.google.protobuf.Descriptors.DescriptorValidationException e) {
            throw new IOException(e);
        }
        for (FileDescriptor f : files) {
            for (Descriptor d : f.getMessageTypes()) types.put(d.getFullName(), d);
        }
        return types;
    }

    private static FileDescriptor annotationsFile() {
        return settings.v1.SettingsOuterClass.getDescriptor();
    }

    /** What this tier says of a document: "ok", or the error as Go would print it. */
    static String verdict(byte[] doc, Descriptor d, boolean any, Map<String, Descriptor> types) {
        UnmarshalOptions o = UnmarshalOptions.defaults();
        if (any) {
            o = o.withTypeResolver(url -> {
                String name = url.substring(url.lastIndexOf('/') + 1);
                return name.equals("any.v1.Leaf") ? types.get(name) : null;
            });
        }
        try {
            o.unmarshalFull(doc, DynamicMessage.newBuilder(d));
            return "ok";
        } catch (PxfException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage(); // never Go's text
        }
    }

    @TestFactory
    List<DynamicTest> everyDocumentGetsGosVerdict() throws IOException {
        Map<String, Descriptor> types = types();
        Path root = CorpusFixtures.dir("");
        List<DynamicTest> tests = new ArrayList<>();
        for (Row r : table()) {
            tests.add(DynamicTest.dynamicTest(r.doc(), () -> {
                Descriptor d = types.get(r.type());
                assertNotNull(d, "no descriptor for " + r.type());
                String got = verdict(Files.readAllBytes(root.resolve(r.doc())), d, r.doc().startsWith("any/"), types);
                String issue = KNOWN_GAPS.get(r.doc());
                if (issue == null) {
                    assertEquals(r.decode(), got, r.doc());
                } else {
                    assertNotEquals(r.decode(), got,
                            r.doc() + " gets Go's verdict now: drop it from KNOWN_GAPS (" + issue + ")");
                }
            }));
        }
        return tests;
    }

    /** The table covers every document the tiers run, so a fixture the spec adds needs a fresh table. */
    @Test
    void theTableCoversEveryDocument() throws IOException {
        Path root = CorpusFixtures.dir("");
        Set<String> docs;
        try (Stream<Path> s = Files.walk(root)) {
            docs = s.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(n -> n.endsWith(".pxf") && !n.endsWith(".expected.pxf") && !n.startsWith("dataset/"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        assertEquals(docs, table().stream().map(Row::doc).collect(Collectors.toCollection(TreeSet::new)),
                "regenerate scripts/go-verdicts/corpus.tsv (see scripts/go-verdicts/main.go)");
        KNOWN_GAPS.keySet().forEach(k -> assertTrue(docs.contains(k), k));
    }
}
