// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
