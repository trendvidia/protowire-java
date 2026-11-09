// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;
import org.protowire.pxf.Format;
import org.protowire.pxf.Parser;
import org.protowire.pxf.PxfException;
import org.protowire.pxf.PxfMeta;
import org.protowire.pxf.SimplePxfRegistry;
import org.protowire.pxf.android.LiteWireReader;
import org.protowire.pxf.android.LiteWireWriter;

/**
 * Every fixture of the spec corpus under {@code testdata/} (protowire's
 * {@code testdata/<dir>/}, byte for byte) through the lite tier: text to wire
 * through {@link LiteWireWriter}, wire to text through {@link LiteWireReader},
 * with the companions protoc-gen-pxf-java-meta generated from the same
 * schemas. The full tier runs the same files (pxf's {@code *CorpusTest});
 * the lite tier has to give the same verdicts and the same bytes.
 *
 * <p>A fixture the lite tier does not meet yet is in {@link #KNOWN_GAPS}
 * against its open issue. A listed fixture that meets its verdict fails the
 * test, so the list only shrinks.
 */
class LiteCorpusTest {

    private static final Path ROOT = Path.of(System.getProperty("protowire.testdata", "testdata-unset"));

    /**
     * {@code <dir>/<file>} to the open issue that fixes it. A key ending in
     * {@code #text} is the error-text check of that fixture, not its verdict.
     */
    private static final Map<String, String> KNOWN_GAPS = Map.ofEntries(
        // rejected, but with IllegalArgumentException rather than PxfException
        Map.entry("nulls/err-list-element.pxf", "#191"),
        Map.entry("nulls/err-oneof-sibling.pxf", "#191"),
        Map.entry("annotations/missing-required.pxf", "#191"),
        // an Any block's @type entry is refused
        Map.entry("any/assignment.pxf", "#213"),
        Map.entry("any/block.pxf", "#213"),
        Map.entry("any/list-element.pxf", "#213"),
        Map.entry("any/map-value.pxf", "#213"),
        Map.entry("any/url-verbatim.pxf", "#213"),
        // a scalar or an assigned block on a repeated field is accepted
        Map.entry("repeated/err-scalar-entries.pxf", "#214"),
        Map.entry("repeated/err-assigned-block.pxf", "#214"),
        // the error text is not Go's
        Map.entry("map-keys/invalid#text", "#215"),
        Map.entry("duplicates/err-oneof-member-twice.pxf#text", "#215"),
        Map.entry("duplicates/err-map-key-twice-int-spellings.pxf#text", "#215"));

    /** Every corpus directory, and the test here that drives it. */
    private static final Set<String> DRIVEN = Set.of(
        "annotations", "any", "bigfloat", "blocks", "dataset", "duplicates", "duration",
        "keyed", "map-keys", "nulls", "repeated", "strings");

    /** The directories whose verdict is the file name: {@code err-*}, or under {@code invalid/}. */
    private static final List<String> BY_NAME = List.of(
        "any", "blocks", "duplicates", "duration", "keyed", "map-keys", "nulls", "repeated", "strings");

    // -- harness -------------------------------------------------------------

    private static Path dir(String dir) {
        Path p = ROOT.resolve(dir);
        assertTrue(Files.isDirectory(p), "missing vendored corpus " + p + " (run through Gradle)");
        return p;
    }

    private static byte[] read(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] fixture(String dir, String name) {
        return read(dir(dir).resolve(name));
    }

    private static String text(String dir, String name) {
        return new String(fixture(dir, name), StandardCharsets.UTF_8);
    }

    /** The documents of a directory, {@code invalid/} included, without the {@code .expected.pxf} halves of pairs. */
    private static List<String> documents(String dir) {
        Path root = dir(dir);
        try (Stream<Path> s = Files.walk(root)) {
            List<String> out = s.filter(Files::isRegularFile)
                .map(p -> root.relativize(p).toString())
                .filter(n -> n.endsWith(".pxf") && !n.endsWith(".expected.pxf"))
                .sorted()
                .collect(Collectors.toList());
            assertTrue(!out.isEmpty(), "empty corpus " + dir);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean rejects(String name) {
        String file = name.substring(name.lastIndexOf('/') + 1);
        return file.startsWith("err-") || name.startsWith("invalid/");
    }

    /** The companion of a generated message, by its proto full name. */
    static PxfMeta meta(String fullName) {
        int dot = fullName.lastIndexOf('.');
        String cls = fullName.substring(0, dot) + "." + fullName.substring(dot + 1) + "PxfMeta";
        try {
            return (PxfMeta) Class.forName(cls).getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no companion " + cls + " for " + fullName, e);
        }
    }

    /** The type a document names in its {@code @type} directive. */
    private static String typeOf(byte[] doc) {
        for (String line : new String(doc, StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith("@type ")) return line.substring("@type ".length()).trim();
        }
        return null;
    }

    /** The Any corpus resolves exactly one URL, as the full tier's resolver does. */
    private static final SimplePxfRegistry ANY_TYPES = new SimplePxfRegistry();
    static {
        ANY_TYPES.register(meta("any.v1.Leaf"));
    }

    private static byte[] decode(String dir, byte[] doc, String type) {
        PxfMeta m = meta(type);
        return dir.equals("any")
            ? LiteWireWriter.encode(Parser.parse(doc), m, ANY_TYPES)
            : LiteWireWriter.encode(Parser.parse(doc), m);
    }

    private static String marshal(byte[] wire, String type) {
        return Format.formatDocument(LiteWireReader.toAst(wire, meta(type)));
    }

    private static void meet(String key, Executable verdict) throws Throwable {
        String issue = KNOWN_GAPS.get(key);
        if (issue == null) {
            verdict.execute();
            return;
        }
        try {
            verdict.execute();
        } catch (Throwable stillAGap) {
            return;
        }
        fail(key + " meets its verdict now: drop it from KNOWN_GAPS (" + issue + ")");
    }

    // -- coverage --------------------------------------------------------------

    @Test
    void everyVendoredDirectoryHasADriver() throws IOException {
        Set<String> dirs;
        try (Stream<Path> s = Files.list(ROOT)) {
            dirs = s.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                .collect(Collectors.toCollection(TreeSet::new));
        }
        assertEquals(new TreeSet<>(DRIVEN), dirs);
    }

    @Test
    void knownGapsNameFixturesThatExist() {
        KNOWN_GAPS.keySet().forEach(k -> assertTrue(Files.exists(ROOT.resolve(k.replace("#text", ""))), k));
    }

    // -- verdicts by file name ---------------------------------------------------

    @TestFactory
    Stream<DynamicTest> everyFixtureMeetsItsVerdict() {
        List<DynamicTest> out = new ArrayList<>();
        for (String dir : BY_NAME) {
            for (String name : documents(dir)) {
                String key = dir + "/" + name;
                out.add(DynamicTest.dynamicTest(key, () -> meet(key, () -> {
                    byte[] doc = fixture(dir, name);
                    String type = typeOf(doc);
                    assertNotNull(type, key + " names no @type");
                    if (rejects(name)) {
                        assertThrows(PxfException.class, () -> decode(dir, doc, type), key);
                    } else {
                        decode(dir, doc, type);
                    }
                })));
            }
        }
        return out.stream();
    }

    /** Every accepted null leaves its field unset, so the message writes no bytes. */
    @Test
    void acceptedNullsLeaveNoFieldSet() {
        int n = 0;
        for (String name : documents("nulls")) {
            if (rejects(name)) continue;
            byte[] doc = fixture("nulls", name);
            assertEquals(0, decode("nulls", doc, typeOf(doc)).length, name);
            n++;
        }
        assertEquals(8, n);
    }

    // -- the error text the full tier pins ---------------------------------------

    @Test
    void duplicateErrorsNameTheField() throws Throwable {
        Map<String, String> want = Map.of(
            "err-scalar-twice.pxf", "field \"s\" is already set",
            "err-message-twice.pxf", "field \"m\" is already set",
            "err-null-then-value.pxf", "field \"w\" is already set",
            "err-nested-scalar-twice.pxf", "field \"a\" is already set",
            "err-oneof-member-twice.pxf", "conflicts with already-set field \"km\"",
            "err-map-key-twice-int-spellings.pxf", "map field \"nums\": key 1 is already set",
            "err-map-key-twice-in-literal.pxf", "map field \"labels\": key \"a\" is already set");
        for (Map.Entry<String, String> w : want.entrySet()) {
            String name = w.getKey();
            meet("duplicates/" + name + "#text", () -> {
                PxfException e = assertThrows(PxfException.class,
                    () -> decode("duplicates", fixture("duplicates", name), "dup.v1.Holder"), name);
                assertTrue(e.getMessage().contains(w.getValue()), name + " -> " + e.getMessage());
            });
        }
    }

    @Test
    void invalidBoolMapKeysNameTheKeyAndTheField() throws Throwable {
        meet("map-keys/invalid#text", LiteCorpusTest::invalidBoolMapKeyTexts);
    }

    private static void invalidBoolMapKeyTexts() {
        for (String name : documents("map-keys")) {
            if (!name.startsWith("invalid/")) continue;
            String stem = name.substring("invalid/".length(), name.length() - ".pxf".length());
            String key = stem.substring(stem.lastIndexOf('-') + 1);
            PxfException e = assertThrows(PxfException.class,
                () -> decode("map-keys", fixture("map-keys", name), "mapkeys.v1.Flags"), name);
            assertTrue(e.getMessage().contains("invalid bool map key"), name + " -> " + e.getMessage());
            assertTrue(e.getMessage().contains(key), name + " must name the key: " + e.getMessage());
            assertTrue(e.getMessage().contains("\"by_flag\""), name + " must name the field: " + e.getMessage());
        }
    }

    @Test
    void aRawNewlineIsRejectedByTheParser() {
        PxfException e = assertThrows(PxfException.class,
            () -> Parser.parse(fixture("strings", "err-raw-newline.pxf")));
        assertTrue(e.getMessage().contains("raw newline"), e.getMessage());
    }

    // -- bytes and text the corpus gives ------------------------------------------

    @Test
    void annotations() throws Throwable {
        String hex = text("annotations", "ok.expected.hex").trim();
        assertEquals(hex, HexFormat.of().formatHex(decode("annotations", fixture("annotations", "ok.pxf"),
            "settings.v1.Settings")), "ok.pxf");
        meet("annotations/missing-required.pxf", () -> assertThrows(PxfException.class, () -> decode("annotations",
            fixture("annotations", "missing-required.pxf"), "settings.v1.Settings"), "missing-required.pxf"));
    }

    private static String lastLine(String doc) {
        String[] lines = doc.strip().split("\n");
        return lines[lines.length - 1];
    }

    @Test
    void bigfloat() {
        int enc = 0, dec = 0, err = 0;
        for (String name : documents("bigfloat")) {
            String stem = name.substring(0, name.length() - ".pxf".length());
            byte[] doc = fixture("bigfloat", name);
            if (stem.startsWith("enc-")) {
                enc++;
                assertEquals(lastLine(new String(doc, StandardCharsets.UTF_8)),
                    marshal(fixture("bigfloat", stem + ".binpb"), "bf.v1.H").strip(), stem);
            } else if (stem.startsWith("dec-")) {
                dec++;
                assertEquals(HexFormat.of().formatHex(fixture("bigfloat", stem + ".binpb")),
                    HexFormat.of().formatHex(decode("bigfloat", doc, "bf.v1.H")), stem);
            } else {
                err++;
                assertThrows(PxfException.class, () -> decode("bigfloat", doc, "bf.v1.H"), stem);
            }
        }
        assertEquals(List.of(13, 18, 4), List.of(enc, dec, err));
    }

    @Test
    void durationsBindWithOneSign() throws Exception {
        record Want(String file, long seconds, int nanos) {}
        for (Want w : List.of(
                new Want("negative-seconds.pxf", -30, 0),
                new Want("negative-fractional.pxf", -1, -500_000_000),
                new Want("negative-multi-segment.pxf", -5400, 0),
                new Want("negative-zero.pxf", 0, 0))) {
            com.google.protobuf.Duration d = duration.v1.Holder.parseFrom(
                decode("duration", fixture("duration", w.file()), "duration.v1.Holder")).getD();
            assertEquals(w.seconds(), d.getSeconds(), w.file());
            assertEquals(w.nanos(), d.getNanos(), w.file());
        }
    }

    /** What a marshaller writes for a pair's input: the expected file's body, after its {@code @type}. */
    private static String body(String doc) {
        int i = doc.indexOf("\n\n");
        return doc.startsWith("@type ") && i >= 0 ? doc.substring(i + 2) : doc;
    }

    @Test
    void aNegativeDurationReEncodesWithOneLeadingSign() {
        byte[] wire = decode("duration", fixture("duration", "roundtrip-negative.pxf"), "duration.v1.Holder");
        assertEquals(body(text("duration", "roundtrip-negative.expected.pxf")), marshal(wire, "duration.v1.Holder"));
    }

    @Test
    void listBindingsConcatenate() throws Exception {
        test.v1.AllTypes m = test.v1.AllTypes.parseFrom(
            decode("repeated", fixture("repeated", "concat-lists.pxf"), "test.v1.AllTypes"));
        assertEquals(List.of("a", "b", "c"), m.getRepeatedStringList());
        assertEquals(List.of("n1", "n2"),
            m.getRepeatedNestedList().stream().map(n -> n.getName()).collect(Collectors.toList()));
    }

    /** The schema-less formatter is pxf-runtime's, shared by both tiers. */
    @Test
    void mapKeyFormatPairs() {
        for (String pair : List.of("fmt-bare-keys", "fmt-dotted-keys", "fmt-keyword-keys")) {
            String expected = text("map-keys", pair + ".expected.pxf");
            assertEquals(expected, Format.formatDocument(Parser.parse(fixture("map-keys", pair + ".pxf"))), pair);
            assertEquals(expected, Format.formatDocument(Parser.parse(expected.getBytes(StandardCharsets.UTF_8))),
                pair + ": expected is a fixed point");
        }
    }

    // -- @dataset ------------------------------------------------------------------

    /** Rejected by the header or a row's syntax: pxf-runtime's parser rejects them. Same table as pxf's DatasetCorpusTest. */
    private static final Set<String> DATASET_PARSE_REJECTS = Set.of(
        "arity-long", "arity-short", "with-body", "with-type", "truncated-row-eof",
        "truncated-row-then-rows", "junk-between-rows", "string-newline-in-cell",
        "duplicate-column", "dotted-overlap", "dotted-overlap-deep", "dotted-empty-segment", "dotted-trailing-dot");

    /**
     * Decided when a row binds to its type. The lite tier binds no row (#212),
     * so these parse and are not checked further.
     */
    private static final Set<String> DATASET_BOUND = Set.of(
        "block-on-scalar", "null-on-scalar-cell", "dotted-oneof-two-members", "dotted-through-repeated",
        "dotted-through-map", "dotted-through-scalar", "dotted-into-timestamp", "dotted-into-wrapper",
        "dotted-into-duration", "dotted-into-bignum", "dotted-unknown-segment", "dotted-required-missing",
        "dotted-bad-header-empty-row", "null-on-required-cell",
        "empty-rows", "rows-end-at-directive", "list-cell", "block-cell", "nested-cells", "cell-framing",
        "null-on-optional-cell", "dotted-column", "dotted-grouping", "dotted-null-leaf", "dotted-oneof",
        "dotted-required", "dotted-with-value-cells", "dotted-into-any", "dotted-into-secret",
        "dotted-bad-header-no-rows");

    @Test
    void datasetParseVerdicts() {
        Set<String> all = new TreeSet<>();
        for (String name : documents("dataset")) {
            String stem = name.substring(0, name.length() - ".pxf".length());
            all.add(stem);
            byte[] doc = fixture("dataset", name);
            if (DATASET_PARSE_REJECTS.contains(stem)) {
                assertThrows(PxfException.class, () -> Parser.parse(doc), stem);
            } else {
                assertTrue(DATASET_BOUND.contains(stem), stem + " has no verdict here");
                assertNotNull(Parser.parse(doc), stem);
            }
        }
        Set<String> known = new TreeSet<>(DATASET_PARSE_REJECTS);
        known.addAll(DATASET_BOUND);
        assertEquals(known, all);
    }
}
