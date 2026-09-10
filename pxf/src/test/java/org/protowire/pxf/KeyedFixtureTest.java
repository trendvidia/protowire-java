// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import keyed.v1.Keyed.Deployment;
import keyed.v1.Keyed.Doc;
import keyed.v1.Keyed.Node;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keyed repeated fields (draft -01 §3.13, protowire#116, #50): the spec
 * repo's {@code testdata/keyed/} fixtures (vendored under
 * {@code src/test/resources/keyed/} from trendvidia/protowire, with
 * {@code keyed.proto} compiled beside the other test protos; the README
 * there states the verdicts), plus the edge cases protowire-go's
 * {@code keyed_test.go} pins.
 */
class KeyedFixtureTest {

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = KeyedFixtureTest.class.getResourceAsStream("/keyed/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return in.readAllBytes();
        }
    }

    private static String text(String name) throws IOException { return new String(fixture(name), StandardCharsets.UTF_8); }

    /** The fixture without its comment lines and blank lines — the body an encoder reproduces. */
    private static String body(String pxf) {
        return Arrays.stream(pxf.split("\n")).filter(l -> !l.startsWith("#") && !l.isBlank()).collect(Collectors.joining("\n")) + "\n";
    }

    private static Descriptor descOf(String name) {
        return switch (name) {
            case "keyed.v1.Node" -> Node.getDescriptor();
            case "keyed.v1.Deployment" -> Deployment.getDescriptor();
            case "keyed.v1.Doc" -> Doc.getDescriptor();
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static Descriptor descOfDoc(byte[] doc) {
        return descOf(Parser.parse(doc).typeUrl());
    }

    private static DynamicMessage decode(byte[] doc) {
        return Pxf.unmarshal(doc, descOfDoc(doc));
    }

    private static String marshal(Message m, String typeUrl) {
        return new String(MarshalOptions.defaults().withTypeUrl(typeUrl).marshal(m), StandardCharsets.UTF_8).replace("\n\n", "\n");
    }

    private static String fmt(byte[] doc) {
        return new String(Pxf.formatDocument(Parser.parse(doc), descOfDoc(doc)), StandardCharsets.UTF_8);
    }

    // -- accept fixtures -------------------------------------------------------

    @Test
    void roundtripKeyed() throws IOException {
        byte[] doc = fixture("roundtrip-keyed.pxf");
        DynamicMessage m = decode(doc);
        Node n = Node.parseFrom(m.toByteArray());
        assertEquals(2, n.getChildrenCount());
        assertEquals("greeting", n.getChildren(0).getId());
        assertEquals("counter_row", n.getChildren(1).getId());
        assertEquals(1, n.getChildren(0).getWeight());
        assertEquals(body(text("roundtrip-keyed.pxf")), marshal(m, "keyed.v1.Node"), "decode → encode reproduces the body");
    }

    @Test
    void roundtripQuoted() throws IOException {
        byte[] doc = fixture("roundtrip-quoted.pxf");
        DynamicMessage m = decode(doc);
        Deployment d = Deployment.parseFrom(m.toByteArray());
        assertEquals(List.of("us-east-1", "eu-west-2"), d.getRegionsList().stream().map(r -> r.getName()).toList());
        assertEquals(body(text("roundtrip-quoted.pxf")), marshal(m, "keyed.v1.Deployment"), "quoting preserved");
    }

    @Test
    void anonymousEquivalence() throws IOException {
        DynamicMessage anon = decode(fixture("anonymous-equivalence.pxf"));
        DynamicMessage keyed = decode(fixture("roundtrip-keyed.pxf"));
        assertEquals(keyed, anon, "the same message");
        assertEquals(body(text("roundtrip-keyed.pxf")), body(fmt(fixture("anonymous-equivalence.pxf"))), "fmt canonicalizes to the keyed form (comments aside)");
    }

    @Test
    void redundantKeyOk() throws IOException {
        Node n = Node.parseFrom(decode(fixture("redundant-key-ok.pxf")).toByteArray());
        assertEquals("greeting", n.getChildren(0).getId());
        String out = fmt(fixture("redundant-key-ok.pxf"));
        assertFalse(out.contains("id = \"greeting\""), "fmt drops the redundant agreeing key: " + out);
        assertTrue(out.contains("  greeting {\n"), out);
    }

    @Test
    void anonymousDuplicateStaysAnonymous() throws IOException {
        byte[] doc = fixture("anonymous-duplicate-ok.pxf");
        DynamicMessage m = decode(doc);
        assertEquals(2, Node.parseFrom(m.toByteArray()).getChildrenCount());
        String enc = marshal(m, "keyed.v1.Node");
        assertTrue(enc.contains("children = [\n"), "encode keeps the anonymous form: " + enc);
        assertTrue(fmt(doc).contains("children = [\n"), "fmt keeps the anonymous form");
    }

    // -- fmt canonicalization pairs --------------------------------------------

    @Test
    void fmtPairs() throws IOException {
        for (String pair : List.of("fmt-unquote", "fmt-anonymous-to-keyed")) {
            String expected = text(pair + ".expected.pxf");
            assertEquals(expected, fmt(fixture(pair + ".pxf")), pair + ": input → expected");
            assertEquals(expected, fmt(fixture(pair + ".expected.pxf")), pair + ": expected is a fixed point");
        }
    }

    // -- reject fixtures -------------------------------------------------------

    private static void assertRejected(String name, String needle) throws IOException {
        byte[] doc = fixture(name);
        PxfException e = assertThrows(PxfException.class, () -> decode(doc), name);
        assertTrue(e.getMessage().contains(needle), name + " -> " + e.getMessage());
    }

    @Test
    void rejectFixtures() throws IOException {
        assertRejected("err-duplicate-key.pxf", "duplicate key \"greeting\" in keyed field \"children\"");
        assertRejected("err-duplicate-key-spelling.pxf", "duplicate key \"greeting\" in keyed field \"children\"");
        assertRejected("err-key-conflict.pxf", "key field \"id\" = \"farewell\" conflicts with entry name \"greeting\" in keyed field \"children\"");
        assertRejected("err-empty-key.pxf", "empty entry name in keyed field \"children\": the empty string is not a valid key");
        assertRejected("err-empty-key-anonymous.pxf", "explicit empty-string assignment to key field \"id\" of keyed field \"children\"");
        assertRejected("err-quoted-name-unkeyed.pxf", "quoted entry name \"a\" is only valid inside a keyed repeated field's block");
    }

    // -- edge semantics protowire-go pins --------------------------------------

    private static Node node(String doc) {
        try {
            return Node.parseFrom(Pxf.unmarshal(doc.getBytes(StandardCharsets.UTF_8), Node.getDescriptor()).toByteArray());
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
    }

    // `children = { ... }` is the unabbreviated spelling of the keyed block
    // form, and `name = { ... }` of an entry.
    @Test
    void assignmentSpelling() {
        Node n = node("id = \"root\"\nchildren = {\n  greeting = { type = \"Label\" }\n  counter_row { type = \"HBox\" }\n}\n");
        assertEquals(List.of("greeting", "counter_row"), n.getChildrenList().stream().map(Node::getId).toList());
        PxfException e = assertThrows(PxfException.class, () -> node("children { greeting = 42 }"));
        assertTrue(e.getMessage().contains("block value"), e.getMessage());
        String out = new String(Pxf.formatDocument(Parser.parse("children = {\n  greeting = { type = \"Label\" }\n}\n"), Node.getDescriptor()), StandardCharsets.UTF_8);
        assertEquals("children {\n  greeting {\n    type = \"Label\"\n  }\n}\n", out);
    }

    // The repeated-field concatenation rule applies unchanged: two bindings
    // concatenate, and duplicate detection is per block.
    @Test
    void concatenationAcrossBlocks() {
        Node n = node("id = \"root\"\nchildren {\n  greeting { type = \"Label\" }\n}\nchildren {\n  greeting { type = \"HBox\" }\n}\n");
        assertEquals(List.of("Label", "HBox"), n.getChildrenList().stream().map(Node::getType).toList());
    }

    @Test
    void nestedBlocks() {
        Node n = node("id = \"root\"\nchildren {\n  outer {\n    type = \"VBox\"\n    children {\n      inner { type = \"Label\" }\n    }\n  }\n}\n");
        assertEquals("outer", n.getChildren(0).getId());
        assertEquals("inner", n.getChildren(0).getChildren(0).getId());
        String out = new String(Pxf.marshal(n), StandardCharsets.UTF_8);
        assertFalse(out.contains("id = \"outer\""), out);
        assertFalse(out.contains("id = \"inner\""), out);
        assertTrue(out.contains("inner {"), out);
    }

    // The encoder picks the form per binding: an element with an empty or
    // duplicate key forces the anonymous form.
    @Test
    void encoderFallsBackToAnonymousWhenNotEligible() {
        Node dup = Node.newBuilder().addChildren(Node.newBuilder().setId("d")).addChildren(Node.newBuilder().setId("d")).build();
        assertTrue(new String(Pxf.marshal(dup), StandardCharsets.UTF_8).contains("children = [\n"));
        Node empty = Node.newBuilder().addChildren(Node.newBuilder().setType("x")).build();
        assertTrue(new String(Pxf.marshal(empty), StandardCharsets.UTF_8).contains("children = [\n"));
        Node ok = Node.newBuilder().addChildren(Node.newBuilder().setId("a.b")).addChildren(Node.newBuilder().setId("true")).build();
        String out = new String(Pxf.marshal(ok), StandardCharsets.UTF_8);
        assertTrue(out.contains("  a.b {\n"), out);
        assertTrue(out.contains("  \"true\" {\n"), out);
    }

    // Presence tracking: the keyed block marks the field present, like any
    // other binding.
    @Test
    void keyedBlockMarksTheFieldPresent() {
        Node.Builder b = Node.newBuilder();
        Result r = Pxf.unmarshalFull("children { a { } }".getBytes(StandardCharsets.UTF_8), b);
        assertTrue(r.isSet("children"));
        assertEquals("a", b.getChildren(0).getId());
    }
}
