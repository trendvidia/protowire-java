// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import com.google.protobuf.Empty;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser tests for the {@code @proto} directive (draft §3.4.5).
 * Four body shapes lexically distinguished: anonymous, named, source,
 * descriptor. Plus reserved-directive-name rejection (draft §3.4.6).
 */
class ProtoDirectiveTest {

    @Test
    void anonymous() {
        var doc = Parser.parse("""
                @proto {
                  string symbol = 1;
                  double price = 2;
                }
                """);
        assertEquals(1, doc.protos().size());
        var pd = doc.protos().get(0);
        assertEquals(Ast.ProtoShape.ANONYMOUS, pd.shape());
        assertEquals("", pd.typeName());
        String body = new String(pd.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("string symbol = 1;"));
        assertTrue(body.contains("double price = 2;"));
    }

    @Test
    void named() {
        var doc = Parser.parse("""
                @proto trades.v1.Trade {
                  string symbol = 1;
                  double price = 2;
                }
                """);
        var pd = doc.protos().get(0);
        assertEquals(Ast.ProtoShape.NAMED, pd.shape());
        assertEquals("trades.v1.Trade", pd.typeName());
        assertTrue(new String(pd.body(), StandardCharsets.UTF_8).contains("string symbol = 1;"));
    }

    @Test
    void source() {
        var doc = Parser.parse("""
                @proto \"""
                syntax = "proto3";
                package trades.v1;
                message Trade { string symbol = 1; }
                \"""
                """);
        var pd = doc.protos().get(0);
        assertEquals(Ast.ProtoShape.SOURCE, pd.shape());
        String body = new String(pd.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("syntax = \"proto3\""));
        assertTrue(body.contains("message Trade"));
    }

    @Test
    void descriptor() {
        byte[] raw = {0x0a, 0x05, 'h', 'e', 'l', 'l', 'o'};
        String b64 = Base64.getEncoder().encodeToString(raw);
        var doc = Parser.parse("@proto b\"" + b64 + "\"");
        var pd = doc.protos().get(0);
        assertEquals(Ast.ProtoShape.DESCRIPTOR, pd.shape());
        assertArrayEquals(raw, pd.body());
    }

    @Test
    void multipleProtos() {
        var doc = Parser.parse("""
                @proto trades.v1.Trade { string symbol = 1; }
                @proto orders.v1.Order { string id = 1; }
                """);
        assertEquals(2, doc.protos().size());
        assertEquals("trades.v1.Trade", doc.protos().get(0).typeName());
        assertEquals("orders.v1.Order", doc.protos().get(1).typeName());
    }

    @Test
    void anonymousFollowedByDataset() {
        // One-shot binding: anonymous @proto types the next directive that
        // requires a typed binding — here, an untyped @dataset.
        var doc = Parser.parse("""
                @proto {
                  string symbol = 1;
                  double price = 2;
                }
                @dataset (symbol, price)
                ("AAPL", 192.34)
                ("MSFT", 410.10)
                """);
        assertEquals(1, doc.protos().size());
        assertEquals(Ast.ProtoShape.ANONYMOUS, doc.protos().get(0).shape());
        var ds = doc.datasets().get(0);
        assertEquals("", ds.type());
        assertEquals(2, ds.rows().size());
    }

    @Test
    void braceNestingInBody() {
        // captureBraceBody must find the matching `}` across nested
        // `message Side { ... }` braces in the proto body.
        var doc = Parser.parse("""
                @proto {
                  message Side {
                    string label = 1;
                  }
                  Side side = 1;
                }
                """);
        String body = new String(doc.protos().get(0).body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("message Side"));
        assertTrue(body.contains("Side side = 1;"));
    }

    @Test
    void rejectsBadShape() {
        var ex = assertThrows(PxfException.class, () -> Parser.parse("@proto 42"));
        assertTrue(ex.getMessage().contains("after @proto"));
    }

    @Test
    void rejectsNamedMissingBrace() {
        var ex = assertThrows(PxfException.class, () -> Parser.parse("@proto trades.v1.Trade 42"));
        assertTrue(ex.getMessage().contains("'{'"));
    }

    @Test
    void rejectsAnonymousUnmatchedBrace() {
        var ex = assertThrows(PxfException.class,
                () -> Parser.parse("@proto { string symbol = 1;"));
        assertTrue(ex.getMessage().contains("unmatched"));
    }

    @Test
    void coexistsWithType() {
        var doc = Parser.parse("""
                @type some.pkg.Foo
                @proto some.pkg.Foo {
                  string name = 1;
                }
                """);
        assertEquals("some.pkg.Foo", doc.typeUrl());
        assertEquals(1, doc.protos().size());
        assertEquals(Ast.ProtoShape.NAMED, doc.protos().get(0).shape());
    }

    // --- A source body must be triple-quoted (#258, protowire-go#305) ---

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    /**
     * The grammar's proto_source_body is a triple_string. A simple-quoted
     * body could carry {@code """} through its escapes, and no formatter can
     * write that back. Parse, unmarshal and a dataset stream's header each
     * refuse it, in protowire-go's words.
     */
    @Test
    void rejectsSimpleQuotedSource() throws Exception {
        String want = "1:8: @proto source body must be a triple-quoted string (\"\"\"...\"\"\")";
        for (String in : new String[]{
                "@proto \"syntax = \\\"proto3\\\";\"",
                "@proto \"syntax = \\\"proto3\\\"; // a \\\"\\\"\\\" b\"", // protowire-go#305's body
                "@proto \"\"",
        }) {
            var ex = assertThrows(PxfException.class, () -> Parser.parse(in), in);
            assertEquals(want, ex.getMessage(), in);

            // Unmarshal reads directives on its own path (FastDecoder).
            ex = assertThrows(PxfException.class, () -> Pxf.unmarshal(utf8(in), Empty.getDescriptor()), in);
            assertEquals(want, ex.getMessage(), in);
            ex = assertThrows(PxfException.class,
                    () -> Pxf.unmarshalFull(utf8(in), Empty.newBuilder()), in);
            assertEquals(want, ex.getMessage(), in);

            // So does a dataset stream's header.
            ex = assertThrows(PxfException.class, () -> new DatasetReader(
                    new ByteArrayInputStream(utf8(in + "\n@dataset T (a)\n(1)\n"))), in);
            assertEquals(want, ex.getMessage(), in);
        }

        // After @type, the position moves but the refusal does not.
        String typed = "@type adversarial.v1.StringHolder\n@proto \"syntax\"\n";
        var ex = assertThrows(PxfException.class, () -> Parser.parse(typed));
        assertEquals("2:8: @proto source body must be a triple-quoted string (\"\"\"...\"\"\")", ex.getMessage());
        ex = assertThrows(PxfException.class, () -> Pxf.unmarshal(utf8(typed), Empty.getDescriptor()));
        assertEquals("2:8: @proto source body must be a triple-quoted string (\"\"\"...\"\"\")", ex.getMessage());
    }

    /** The triple-quoted form still reads, on the same line or the next, on every path. */
    @Test
    void acceptsTripleQuotedSource() throws Exception {
        for (String in : new String[]{
                "@proto \"\"\"syntax = \"proto3\";\"\"\"\n",
                "@proto\n\"\"\"syntax = \"proto3\";\"\"\"\n",
        }) {
            var doc = Parser.parse(in);
            assertEquals(Ast.ProtoShape.SOURCE, doc.protos().get(0).shape(), in);
            assertEquals("syntax = \"proto3\";", new String(doc.protos().get(0).body(), StandardCharsets.UTF_8), in);
            assertDoesNotThrow(() -> Pxf.unmarshal(utf8(in), Empty.getDescriptor()), in);
            var full = Pxf.unmarshalFull(utf8(in), Empty.newBuilder());
            assertEquals(1, full.protos().size(), in);
            var tr = new DatasetReader(new ByteArrayInputStream(utf8(in + "@dataset T (a)\n")));
            assertEquals("T", tr.type(), in);
        }
    }

    /**
     * The descriptor form is a bytes literal, not a string, and stays legal
     * (proto_descr_body = bytes): even an empty one, on every path.
     */
    @Test
    void acceptsDescriptorBytesForm() throws Exception {
        String in = "@proto b\"\"\n";
        var doc = Parser.parse(in);
        assertEquals(Ast.ProtoShape.DESCRIPTOR, doc.protos().get(0).shape());
        assertEquals(0, doc.protos().get(0).body().length);
        assertDoesNotThrow(() -> Pxf.unmarshal(utf8(in), Empty.getDescriptor()));
        assertEquals(Ast.ProtoShape.DESCRIPTOR,
                Pxf.unmarshalFull(utf8(in), Empty.newBuilder()).protos().get(0).shape());
        assertEquals("T", new DatasetReader(new ByteArrayInputStream(utf8(in + "@dataset T (a)\n"))).type());
        assertEquals(0, Parser.parseTolerant(utf8(in)).errors().size());
    }

    /** A tolerant parse reports the simple-quoted body once and parses on. */
    @Test
    void tolerantReportsSimpleQuotedSourceAndParsesOn() {
        var t = Parser.parseTolerant(utf8("@proto \"syntax\"\n@type a.v1.M\n\nx = 1\n"));
        assertEquals(1, t.errors().size(), t.errors().toString());
        assertEquals("1:8: @proto source body must be a triple-quoted string (\"\"\"...\"\"\")",
                t.errors().get(0).getMessage());
        assertTrue(t.document().protos().isEmpty());
        assertEquals("a.v1.M", t.document().typeUrl());
        assertEquals(1, t.document().entries().size());
    }

    // --- Reserved directive names (draft §3.4.6) ---

    @Test
    void rejectsReservedDirectiveNames() {
        for (String name : new String[]{"table", "datasource", "view", "procedure", "function", "permissions"}) {
            var ex = assertThrows(PxfException.class,
                    () -> Parser.parse("@" + name + " { x = 1 }"),
                    "@" + name + " should be rejected");
            assertTrue(ex.getMessage().contains("spec-reserved"),
                    "@" + name + " error should mention spec-reserved");
        }
    }

    // --- ProtoShape enum coverage ---

    @Test
    void protoShapeValues() {
        assertEquals(4, Ast.ProtoShape.values().length);
        assertEquals(Ast.ProtoShape.ANONYMOUS, Ast.ProtoShape.valueOf("ANONYMOUS"));
        assertEquals(Ast.ProtoShape.NAMED, Ast.ProtoShape.valueOf("NAMED"));
        assertEquals(Ast.ProtoShape.SOURCE, Ast.ProtoShape.valueOf("SOURCE"));
        assertEquals(Ast.ProtoShape.DESCRIPTOR, Ast.ProtoShape.valueOf("DESCRIPTOR"));
    }
}
