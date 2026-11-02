// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf.android;

import keyed.v1.CatalogPxfMeta;
import keyed.v1.DeploymentPxfMeta;
import keyed.v1.DocPxfMeta;
import keyed.v1.NodePxfMeta;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.Ast;
import org.protowire.pxf.Format;
import org.protowire.pxf.Parser;
import org.protowire.pxf.PxfEnum;
import org.protowire.pxf.PxfException;
import org.protowire.pxf.PxfMeta;
import org.protowire.pxf.PxfRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spec repo's {@code testdata/keyed/} fixtures through the lite tier, on the
 * companions {@code protoc-gen-pxf-java-meta} generates for {@code keyed.proto}
 * in {@code :lite-fixtures}. {@link Meta} remains for the edges that schema does
 * not have.
 */
final class KeyedLiteTest {

    private static final int STRING = 9;
    private static final int MESSAGE = 11;

    /** Just the tables keyed binding reads; the rest are empty. */
    private static class Meta implements PxfMeta {
        final String fullName;
        final Map<String, Integer> numbers;
        final Map<Integer, Integer> kinds;
        final Set<Integer> repeated;
        final Map<Integer, String> keys;
        final Map<Integer, PxfMeta> nested = new java.util.HashMap<>();
        Map<Integer, String> defaults = Map.of();
        Set<Integer> required = Set.of();
    
        Meta(String fullName, Map<String, Integer> numbers, Map<Integer, Integer> kinds,
             Set<Integer> repeated, Map<Integer, String> keys) {
            this.fullName = fullName;
            this.numbers = numbers;
            this.kinds = kinds;
            this.repeated = repeated;
            this.keys = keys;
        }

        @Override public Map<String, Integer>  fieldNumbers()      { return numbers; }
        @Override public Map<Integer, Integer> fieldKinds()        { return kinds; }
        @Override public Map<Integer, Integer> wireTypes()         { return Map.of(); }
        @Override public Set<Integer>          repeatedFields()    { return repeated; }
        @Override public Set<Integer>          packedFields()      { return Set.of(); }
        @Override public Map<Integer, String>  messageTypes()      { return Map.of(); }
        @Override public Map<Integer, String>  enumTypes()         { return Map.of(); }
        @Override public Set<Integer>          requiredFields()    { return required; }
        @Override public Map<Integer, String>  defaults()          { return defaults; }
        @Override public int                   sbeTemplateId()     { return -1; }
        @Override public Map<Integer, Integer> sbeFieldLengths()   { return Map.of(); }
        @Override public Map<Integer, String>  sbeFieldEncodings() { return Map.of(); }
        @Override public Map<Integer, String>  oneofOf()           { return Map.of(); }
        @Override public String                fullName()          { return fullName; }
        @Override public Map<Integer, PxfMeta> nestedMetas()       { return nested; }
        @Override public Map<Integer, String>  keyFields()         { return keys; }
    }

    private static final PxfMeta NODE = NodePxfMeta.INSTANCE;
    private static final PxfMeta DEPLOYMENT = DeploymentPxfMeta.INSTANCE;
    private static final PxfMeta DOC = DocPxfMeta.INSTANCE;
    private static final PxfMeta CATALOG = CatalogPxfMeta.INSTANCE;

    private static final PxfRegistry NO_REGISTRY = new PxfRegistry() {
        @Override public PxfMeta lookupMessage(String fullName) { throw new AssertionError(fullName); }
        @Override public PxfEnum lookupEnum(String fullName) { throw new AssertionError(fullName); }
    };

    private static String fixture(String name) throws IOException {
        try (InputStream in = KeyedLiteTest.class.getResourceAsStream("/keyed/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The fixture's {@code @type} short name picks the message it binds. */
    private static PxfMeta metaOf(String doc) {
        if (doc.contains("keyed.v1.Deployment")) return DEPLOYMENT;
        if (doc.contains("keyed.v1.Doc")) return DOC;
        if (doc.contains("keyed.v1.Catalog")) return CATALOG;
        assertTrue(doc.contains("keyed.v1.Node"), doc);
        return NODE;
    }

    private static byte[] encode(String doc, PxfMeta meta) {
        return LiteWireWriter.encode(Parser.parse(doc), meta, NO_REGISTRY);
    }

    private static String format(byte[] wire, PxfMeta meta) {
        return Format.formatDocument(LiteWireReader.toAst(wire, meta, NO_REGISTRY));
    }

    /** Text through the wire and back: decode, then encode. */
    private static String roundTrip(String doc) {
        PxfMeta meta = metaOf(doc);
        return format(encode(doc, meta), meta);
    }

    /** The document without its {@code @type} line, comments and blank lines. */
    private static String body(String doc) {
        List<String> kept = new ArrayList<>();
        for (String line : doc.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#") && !t.startsWith("//") && !t.startsWith("@type")) {
                kept.add(line);
            }
        }
        return String.join("\n", kept);
    }

    // -- accept fixtures -------------------------------------------------------

    @Test
    void roundtripFixturesReproduceTheirBody() throws IOException {
        for (String name : List.of("roundtrip-keyed.pxf", "roundtrip-quoted.pxf")) {
            String doc = fixture(name);
            assertEquals(body(doc), body(roundTrip(doc)), name);
        }
    }

    @Test
    void anonymousFormDecodesToTheSameMessage() throws IOException {
        String anonymous = fixture("anonymous-equivalence.pxf");
        String keyed = fixture("roundtrip-keyed.pxf");
        assertEquals(body(roundTrip(keyed)), body(roundTrip(anonymous)));
    }

    @Test
    void anAgreeingKeyAssignmentIsDropped() throws IOException {
        String out = roundTrip(fixture("redundant-key-ok.pxf"));
        assertTrue(out.contains("greeting {"), out);
        assertFalse(out.contains("id = \"greeting\""), out);
    }

    @Test
    void duplicateKeysStayAnonymous() throws IOException {
        String doc = fixture("anonymous-duplicate-ok.pxf");
        assertEquals(body(doc).replaceAll("[\\s,]+", ""), body(roundTrip(doc)).replaceAll("[\\s,]+", ""),
            "duplicates cannot take the keyed form");
    }

    @Test
    void fmtPairsReachTheirExpectedForm() throws IOException {
        for (String pair : List.of("fmt-unquote", "fmt-anonymous-to-keyed")) {
            String expected = fixture(pair + ".expected.pxf");
            assertEquals(body(expected), body(roundTrip(fixture(pair + ".pxf"))), pair);
            assertEquals(body(expected), body(roundTrip(expected)), pair + " fixed point");
        }
    }

    @Test
    void aKeyInAOneofTakesTheEntryName() throws IOException {
        byte[] wire = encode(fixture("key-oneof-ok.pxf"), CATALOG);
        // choices (1) { name (1) "alpha" }, { name "beta" }, { other (2) "x" }
        assertArrayEquals(HexFormat.of().parseHex("0a070a05616c706861" + "0a060a0462657461" + "0a03120178"), wire);
    }

    @Test
    void anAnonymousElementMayNullAnOptionalKey() throws IOException {
        // tags (2) { }: the key stays absent, so no name can carry the element.
        assertArrayEquals(HexFormat.of().parseHex("1200"), encode(fixture("key-null-anonymous-ok.pxf"), CATALOG));
    }

    // -- reject fixtures -------------------------------------------------------

    private static void assertRejected(String doc, PxfMeta meta, String needle) {
        PxfException e = assertThrows(PxfException.class, () -> encode(doc, meta), doc);
        assertTrue(e.getMessage().contains(needle), e.getMessage());
    }

    @Test
    void rejectFixtures() throws IOException {
        Map<String, String> cases = Map.ofEntries(
            Map.entry("err-duplicate-key.pxf", "duplicate key \"greeting\" in keyed field \"children\""),
            Map.entry("err-duplicate-key-spelling.pxf", "duplicate key \"greeting\" in keyed field \"children\""),
            Map.entry("err-key-conflict.pxf", "key field \"id\" = \"farewell\" conflicts with entry name \"greeting\" in keyed field \"children\""),
            Map.entry("err-empty-key.pxf", "empty entry name in keyed field \"children\": the empty string is not a valid key"),
            Map.entry("err-empty-key-anonymous.pxf", "explicit empty-string assignment to key field \"id\" of keyed field \"children\""),
            Map.entry("err-quoted-name-unkeyed.pxf", "quoted entry name \"a\" is only valid inside a keyed repeated field's block"),
            Map.entry("err-key-null.pxf", "null on key field \"name\" conflicts with entry name \"alpha\" in keyed field \"tags\""),
            Map.entry("err-key-oneof-sibling.pxf", "field \"other\" shares oneof \"pick\" with key field \"name\""),
            Map.entry("err-key-oneof-sibling-null.pxf", "field \"other\" shares oneof \"pick\" with key field \"name\""));
        for (Map.Entry<String, String> c : cases.entrySet()) {
            String doc = fixture(c.getKey());
            assertRejected(doc, metaOf(doc), c.getValue());
        }
    }

    // -- wire shape and edges --------------------------------------------------

    @Test
    void entryNameIsTheKeyFieldOnTheWire() {
        // children (4, LEN 7) { id (1, LEN 5) "alpha" }
        assertArrayEquals(HexFormat.of().parseHex("22070a05616c706861"),
            encode("children { alpha {} }", NODE));
    }

    @Test
    void entriesKeepDocumentOrder() {
        assertEquals("children {\n  b {\n  }\n  a {\n  }\n}\n".replaceAll("\\s+", ""),
            format(encode("children { b {} a {} }", NODE), NODE).replaceAll("\\s+", ""));
    }

    @Test
    void assignmentSpellingsAreTheBlockForm() {
        byte[] block = encode("children { alpha { weight = 1 } }", NODE);
        assertArrayEquals(block, encode("children = { alpha { weight = 1 } }", NODE));
        assertArrayEquals(block, encode("children { alpha = { weight = 1 } }", NODE));
        assertArrayEquals(block, encode("children { \"alpha\" { weight = 1 } }", NODE));
    }

    @Test
    void keyedFormRecursesThroughElements() {
        String doc = "children { a { children { b { weight = 2 } } } }";
        assertEquals(doc.replaceAll("\\s+", ""), format(encode(doc, NODE), NODE).replaceAll("\\s+", ""));
    }

    @Test
    void entryNeedsABlockValue() {
        assertRejected("children { alpha = 1 }", NODE, "must have a block value");
    }

    @Test
    void nullOnTheKeyConflictsWithTheName() {
        assertRejected("children { alpha { id = null } }", NODE,
            "null on key field \"id\" conflicts with entry name \"alpha\"");
    }

    @Test
    void theNameSetsTheKeyForDefaultsAndRequired() {
        Meta elem = new Meta("t.Elem", Map.of("id", 1), Map.of(1, STRING), Set.of(), Map.of());
        elem.defaults = Map.of(1, "dflt");
        elem.required = Set.of(1);
        Meta holder = new Meta("t.Holder", Map.of("kd", 1), Map.of(1, MESSAGE), Set.of(1), Map.of(1, "id"));
        holder.nested.put(1, elem);
        // kd (1, LEN 7) { id (1, LEN 5) "alpha" }: the default did not replace it.
        assertArrayEquals(HexFormat.of().parseHex("0a070a05616c706861"), encode("kd { alpha {} }", holder));
    }

    @Test
    void anElementWithoutAKeyStaysAnonymous() {
        // The second element has no id, so no name can carry it.
        byte[] wire = encode("children = [ { id = \"a\" }, { weight = 1 } ]", NODE);
        assertTrue(format(wire, NODE).contains("children = ["), format(wire, NODE));
    }

    @Test
    void aPlainRepeatedFieldRefusesTheBlockForm() {
        assertRejected("items { id = \"a\" }", DOC, "repeated field \"items\" must use list syntax");
        assertRejected("items { a { } }", DOC, "repeated field \"items\" must use list syntax");
    }

    @Test
    void aMetaWithoutTheTableBindsAsPlain() {
        Meta plain = new Meta("keyed.v1.Node",
            NODE.fieldNumbers(), NODE.fieldKinds(), NODE.repeatedFields(), Map.of());
        plain.nested.put(4, plain);
        byte[] wire = encode("children = [ { id = \"a\" } ]", plain);
        assertTrue(format(wire, plain).contains("children = ["), format(wire, plain));
    }
}
