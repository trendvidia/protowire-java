// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import blocks.v1.Blocks.Doc;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DynamicMessage;
import dup.v1.Duplicates.Holder;
import dup.v1.Duplicates.Inner;
import nulls.v1.Nulls;
import nulls.v1.NullsProto2;
import org.junit.jupiter.api.Test;
import strings.v1.Strings;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the spec repo's {@code testdata/{blocks,duplicates,nulls,strings}/}
 * corpora, vendored under {@code src/test/resources/corpus/} from
 * trendvidia/protowire 234cf29, through this port's decoder on both presence
 * paths and through the schema-less parse. Each corpus's README states the
 * verdicts.
 */
class RejectRulesCorpusTest {

    private static DynamicMessage decode(byte[] data, Descriptor d) {
        return Pxf.unmarshal(data, d);
    }

    private static Result decodeFull(byte[] data, Descriptor d) {
        DynamicMessage.Builder b = DynamicMessage.newBuilder(d);
        return Pxf.unmarshalFull(data, b);
    }

    private static void assertVerdict(CorpusFixtures.Fixture f, Descriptor d) {
        if (f.rejects()) {
            assertThrows(PxfException.class, () -> decode(f.data(), d), f.name() + " (unmarshal)");
            assertThrows(PxfException.class, () -> decodeFull(f.data(), d), f.name() + " (unmarshalFull)");
        } else {
            decode(f.data(), d);
            decodeFull(f.data(), d);
        }
    }

    private static int run(String dir, Function<CorpusFixtures.Fixture, Descriptor> schema) throws IOException {
        List<CorpusFixtures.Fixture> all = CorpusFixtures.load(dir);
        for (CorpusFixtures.Fixture f : all) {
            Descriptor d = schema.apply(f);
            if (d != null) assertVerdict(f, d);
        }
        return all.size();
    }

    // -- blocks, #106 --------------------------------------------------------

    @Test
    void blocks() throws IOException {
        assertEquals(7, run("blocks", f -> Doc.getDescriptor()));
    }

    @Test
    void blocksSchemaLessParse() throws IOException {
        for (CorpusFixtures.Fixture f : CorpusFixtures.load("blocks")) {
            if (f.rejects()) assertThrows(PxfException.class, () -> Pxf.parse(f.data()), f.name());
            else assertNotNull(Pxf.parse(f.data()), f.name());
        }
    }

    @Test
    void blocksBindTheirValues() throws IOException {
        Map<String, byte[]> by = byName("blocks");
        for (String name : List.of("space-in-block.pxf", "newline-in-block.pxf")) {
            Doc.Builder b = Doc.newBuilder();
            Pxf.unmarshal(by.get(name), b);
            assertEquals("Berlin", b.getAddress().getCity(), name);
            assertEquals("10115", b.getAddress().getZip(), name);
        }
        Doc.Builder b = Doc.newBuilder();
        Pxf.unmarshal(by.get("comma-between-elements.pxf"), b);
        assertEquals(List.of("greeting", "title"), b.getChildrenList().stream().map(c -> c.getId()).toList());
    }

    // -- duplicates, #103 and #105 -------------------------------------------

    @Test
    void duplicates() throws IOException {
        assertEquals(19, run("duplicates", f -> Holder.getDescriptor()));
    }

    /** A second binding is legal syntax: the rule is the schema layer's. */
    @Test
    void duplicatesParseWithoutASchema() throws IOException {
        for (CorpusFixtures.Fixture f : CorpusFixtures.load("duplicates")) {
            assertNotNull(Pxf.parse(f.data()), f.name());
        }
    }

    @Test
    void duplicateErrorsNameTheField() throws IOException {
        Map<String, byte[]> by = byName("duplicates");
        Map<String, String> want = Map.of(
                "err-scalar-twice.pxf", "field \"s\" is already set",
                "err-message-twice.pxf", "field \"m\" is already set",
                "err-null-then-value.pxf", "field \"w\" is already set",
                "err-nested-scalar-twice.pxf", "field \"a\" is already set",
                "err-oneof-member-twice.pxf", "conflicts with already-set field \"km\"",
                "err-map-key-twice-int-spellings.pxf", "map field \"nums\": key 1 is already set",
                "err-map-key-twice-in-literal.pxf", "map field \"labels\": key \"a\" is already set");
        want.forEach((name, text) -> {
            PxfException e = assertThrows(PxfException.class, () -> decode(by.get(name), Holder.getDescriptor()), name);
            assertTrue(e.getMessage().contains(text), name + " -> " + e.getMessage());
        });
    }

    @Test
    void acceptedDuplicatesKeepEveryBinding() throws IOException {
        Map<String, byte[]> by = byName("duplicates");
        Holder.Builder b = Holder.newBuilder();
        Pxf.unmarshal(by.get("repeated-twice.pxf"), b);
        assertEquals(List.of(1, 2), b.getItemsList().stream().map(Inner::getA).toList());

        b = Holder.newBuilder();
        Pxf.unmarshal(by.get("map-twice-distinct-keys.pxf"), b);
        assertEquals(Map.of("a", "1", "b", "2"), b.getLabelsMap());

        b = Holder.newBuilder();
        Pxf.unmarshal(by.get("map-key-once-per-body.pxf"), b);
        assertEquals("1", b.getItems(0).getTagsOrThrow("a"));
        assertEquals("2", b.getItems(1).getTagsOrThrow("a"));
    }

    // -- nulls, #102 ---------------------------------------------------------

    private static final Map<String, Descriptor> NULL_SCHEMAS = Map.of(
            "err-proto2-required.pxf", NullsProto2.Strict.getDescriptor(),
            "proto2-scalar.pxf", NullsProto2.Legacy.getDescriptor(),
            "required-optional.pxf", Nulls.Required.getDescriptor(),
            "default-optional.pxf", Nulls.Defaulted.getDescriptor());

    /** {@code nulls.ed.v1.Ed}, loaded from a descriptor set: see {@link #nullsEditions}. */
    private static boolean editions(CorpusFixtures.Fixture f) {
        return f.name().contains("editions");
    }

    @Test
    void nulls() throws IOException {
        assertEquals(19, run("nulls", f -> editions(f) ? null
                : NULL_SCHEMAS.getOrDefault(f.name(), Nulls.Holder.getDescriptor())));
    }

    @Test
    void nullsParseWithoutASchema() throws IOException {
        for (CorpusFixtures.Fixture f : CorpusFixtures.load("nulls")) {
            assertNotNull(Pxf.parse(f.data()), f.name());
        }
    }

    @Test
    void acceptedNullsLeaveTheFieldUnsetAndPresent() throws IOException {
        Map<String, String> field = Map.of(
                "message.pxf", "msg", "wrapper.pxf", "w", "optional-scalar.pxf", "o",
                "oneof-scalar.pxf", "ks", "oneof-message.pxf", "km", "proto2-scalar.pxf", "p",
                "required-optional.pxf", "r", "default-optional.pxf", "d");
        Map<String, byte[]> by = byName("nulls");
        field.forEach((name, fd) -> {
            Descriptor d = NULL_SCHEMAS.getOrDefault(name, Nulls.Holder.getDescriptor());
            DynamicMessage.Builder b = DynamicMessage.newBuilder(d);
            Result r = Pxf.unmarshalFull(by.get(name), b);
            assertTrue(b.build().getAllFields().isEmpty(), name + " left a field set");
            assertTrue(r.isNull(fd), name + " is not present-but-null");
        });
    }

    /**
     * The corpus's editions schema (protowire#380), which protoc 3.25.5 reads
     * only behind {@code --experimental_editions}, so {@code
     * nulls_editions.binpb} was built with that flag and is checked in. Pinned
     * rather than run: this says what the 3.25.5 runtime does with the
     * descriptor. The day the runtime moves past 3.25 this fails, and the two
     * editions fixtures join {@link #nulls()}.
     */
    @Test
    void nullsEditions() throws Exception {
        FileDescriptorProto proto;
        try (InputStream in = getClass().getResourceAsStream("/corpus/nulls/nulls_editions.binpb")) {
            assertNotNull(in, "missing nulls_editions.binpb");
            proto = FileDescriptorSet.parseFrom(in).getFile(0);
        }
        assertEquals("editions", proto.getSyntax());
        FileDescriptor fd = FileDescriptor.buildFrom(proto, new FileDescriptor[0]);
        Descriptor ed = fd.findMessageTypeByName("Ed");
        // The runtime consults no features: neither scalar has presence, so
        // the corpus's EXPLICIT accept fixture would be rejected.
        assertFalse(ed.findFieldByName("explicit_scalar").hasPresence());
        assertFalse(ed.findFieldByName("implicit_scalar").hasPresence());
    }

    // -- strings, #102 -------------------------------------------------------

    @Test
    void strings() throws IOException {
        assertEquals(4, run("strings", f -> Strings.Holder.getDescriptor()));
    }

    @Test
    void stringsSchemaLessParse() throws IOException {
        for (CorpusFixtures.Fixture f : CorpusFixtures.load("strings")) {
            if (f.rejects()) {
                PxfException e = assertThrows(PxfException.class, () -> Pxf.parse(f.data()), f.name());
                assertTrue(e.getMessage().contains("raw newline"), e.getMessage());
            } else {
                assertNotNull(Pxf.parse(f.data()), f.name());
            }
        }
    }

    @Test
    void stringsBindTheirValues() throws IOException {
        Map<String, byte[]> by = byName("strings");
        for (String name : List.of("escaped-newline.pxf", "triple-quoted.pxf")) {
            Strings.Holder.Builder b = Strings.Holder.newBuilder();
            Pxf.unmarshal(by.get(name), b);
            assertEquals("x\ny", b.getS(), name);
        }
        Strings.Holder.Builder b = Strings.Holder.newBuilder();
        Pxf.unmarshal(by.get("newline-after-string.pxf"), b);
        assertEquals("x", b.getS());
        assertEquals("y", b.getT());
    }

    @Test
    void rawNewlineErrorIsAtTheString() {
        PxfException e = assertThrows(PxfException.class,
                () -> Pxf.unmarshal("s = \"x\ny\"\n".getBytes(), Strings.Holder.newBuilder()));
        assertEquals(1, e.position().line());
        assertEquals(5, e.position().column());
    }

    // -- @dataset, #102: the same rules in a cell, on both dataset paths -------

    private static void bindAll(byte[] doc) {
        for (Ast.DatasetDirective t : Pxf.parse(doc).datasets()) {
            for (Ast.DatasetRow row : t.rows()) {
                BindRow.bindRow(org.protowire.pxf.testproto.AllTypes.newBuilder(), t.columns(), row);
            }
        }
    }

    private static void streamAll(byte[] doc) throws IOException {
        DatasetReader r = new DatasetReader(new ByteArrayInputStream(doc));
        while (r.scan(org.protowire.pxf.testproto.AllTypes.newBuilder())) { /* the row is the point */ }
    }

    @Test
    void datasetCellsMeetTheRulesOnBothPaths() throws IOException {
        Map<String, byte[]> by = byName("dataset");
        for (String name : List.of("null-on-scalar-cell.pxf", "string-newline-in-cell.pxf")) {
            assertThrows(PxfException.class, () -> bindAll(by.get(name)), name + " (parse + bindRow)");
            assertThrows(PxfException.class, () -> streamAll(by.get(name)), name + " (DatasetReader)");
        }
    }

    private static Map<String, byte[]> byName(String dir) throws IOException {
        Map<String, byte[]> out = new java.util.HashMap<>();
        for (CorpusFixtures.Fixture f : CorpusFixtures.load(dir)) out.put(f.name(), f.data());
        return out;
    }
}
