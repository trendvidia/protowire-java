// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser-side tests for the {@code @table} directive (draft §3.4.4).
 * Mirrors the Go-port tests in encoding/pxf/table_test.go.
 */
class TableParserTest {

    @Test
    void basicTable() {
        var doc = Parser.parse("""
                @table trades.v1.Trade (symbol, price, qty)
                ("AAPL", 192.34, 100)
                ("MSFT", 410.10, 50)
                """);
        assertEquals(1, doc.tables().size());
        var t = doc.tables().get(0);
        assertEquals("trades.v1.Trade", t.type());
        assertEquals(List.of("symbol", "price", "qty"), t.columns());
        assertEquals(2, t.rows().size());
        assertEquals(3, t.rows().get(0).cells().size());
    }

    @Test
    void emptyTable() {
        var doc = Parser.parse("@table trades.v1.Trade (symbol, price)");
        assertEquals(1, doc.tables().size());
        assertEquals(List.of("symbol", "price"), doc.tables().get(0).columns());
        assertEquals(0, doc.tables().get(0).rows().size());
    }

    // --- Three cell states ---

    @Test
    void threeCellStates() {
        var doc = Parser.parse("""
                @table trades.v1.Trade (symbol, price, qty)
                ("AAPL", 192.34, 100)
                ("MSFT", null, 50)
                ("GOOG", , )
                """);
        var rows = doc.tables().get(0).rows();
        // Row 1: all present.
        for (Ast.Value c : rows.get(0).cells()) assertNotNull(c);
        // Row 2: middle is *NullVal.
        assertNotNull(rows.get(1).cells().get(0));
        assertTrue(rows.get(1).cells().get(1) instanceof Ast.NullVal);
        assertNotNull(rows.get(1).cells().get(2));
        // Row 3: trailing cells empty (absent ⇒ null).
        assertNotNull(rows.get(2).cells().get(0));
        assertNull(rows.get(2).cells().get(1));
        assertNull(rows.get(2).cells().get(2));
    }

    @Test
    void leadingEmptyCell() {
        var doc = Parser.parse("""
                @table T (a, b)
                ( , 192.34)
                """);
        var row = doc.tables().get(0).rows().get(0);
        assertNull(row.cells().get(0));
        assertNotNull(row.cells().get(1));
    }

    @Test
    void allEmptyRow() {
        var doc = Parser.parse("""
                @table T (a, b, c)
                (,,)
                """);
        var row = doc.tables().get(0).rows().get(0);
        assertEquals(3, row.cells().size());
        for (Ast.Value c : row.cells()) assertNull(c);
    }

    // --- Arity ---

    @Test
    void arityShortRejected() {
        var ex = assertThrows(PxfException.class, () -> Parser.parse("""
                @table T (symbol, price, qty)
                ("AAPL", 1.0)
                """));
        assertTrue(ex.getMessage().contains("2 cells, expected 3"));
    }

    @Test
    void arityLongRejected() {
        var ex = assertThrows(PxfException.class, () -> Parser.parse("""
                @table T (symbol, price)
                ("AAPL", 1.0, 100)
                """));
        assertTrue(ex.getMessage().contains("3 cells, expected 2"));
    }

    // --- v1 cell-grammar restrictions ---

    @Test
    void listCellRejected() {
        var ex = assertThrows(PxfException.class, () -> Parser.parse("""
                @table T (symbol, tags)
                ("AAPL", ["tech", "blue-chip"])
                """));
        assertTrue(ex.getMessage().contains("list values"));
    }

    @Test
    void blockCellRejected() {
        var ex = assertThrows(PxfException.class, () -> Parser.parse("""
                @table T (symbol, meta)
                ("AAPL", { exchange = "NASDAQ" })
                """));
        assertTrue(ex.getMessage().contains("block values"));
    }

    // --- Column entries ---

    @Test
    void dottedColumnRejected() {
        var ex = assertThrows(PxfException.class, () -> Parser.parse("""
                @table T (symbol, meta.exchange)
                ("AAPL", "NASDAQ")
                """));
        assertTrue(ex.getMessage().contains("dotted column paths"));
    }

    @Test
    void emptyColumnListRejected() {
        var ex = assertThrows(PxfException.class, () -> Parser.parse("@table T ()"));
        assertTrue(ex.getMessage().contains("at least one field name"));
    }

    // --- Standalone constraint ---

    @Test
    void atTypeWithAtTableRejected() {
        var ex = assertThrows(PxfException.class, () -> Parser.parse("""
                @type trades.v1.Wrapper
                @table trades.v1.Trade (symbol)
                ("AAPL")
                """));
        assertTrue(ex.getMessage().contains("@type"));
    }

    @Test
    void atTableWithBodyEntriesRejected() {
        var ex = assertThrows(PxfException.class, () -> Parser.parse("""
                @table trades.v1.Trade (symbol)
                ("AAPL")
                extra = "stray"
                """));
        assertTrue(ex.getMessage().contains("top-level field"));
    }

    // --- Multiple tables ---

    @Test
    void multipleTablesOrderPreserved() {
        var doc = Parser.parse("""
                @table events.v1.Created (id)
                ("e-1")
                ("e-2")
                @table events.v1.Deleted (id)
                ("e-9")
                """);
        assertEquals(2, doc.tables().size());
        assertEquals("events.v1.Created", doc.tables().get(0).type());
        assertEquals("events.v1.Deleted", doc.tables().get(1).type());
        assertEquals(2, doc.tables().get(0).rows().size());
        assertEquals(1, doc.tables().get(1).rows().size());
    }

    // --- Cell variants (smoke check that timestamp, duration, bytes, etc. land correctly) ---

    @Test
    void cellVariants() {
        var doc = Parser.parse("""
                @table t.T (s, i, f, b, by, ts, d, e, n)
                ("hi", 42, 3.14, true, b"aGVsbG8=", 2026-05-12T10:00:00Z, 1h30m, ENUM_VAL, null)
                """);
        var cells = doc.tables().get(0).rows().get(0).cells();
        assertTrue(cells.get(0) instanceof Ast.StringVal);
        assertTrue(cells.get(1) instanceof Ast.IntVal);
        assertTrue(cells.get(2) instanceof Ast.FloatVal);
        assertTrue(cells.get(3) instanceof Ast.BoolVal);
        assertTrue(cells.get(4) instanceof Ast.BytesVal);
        assertTrue(cells.get(5) instanceof Ast.TimestampVal);
        assertTrue(cells.get(6) instanceof Ast.DurationVal);
        assertTrue(cells.get(7) instanceof Ast.IdentVal);
        assertTrue(cells.get(8) instanceof Ast.NullVal);
    }
}
