// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.AllTypes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link Result#directives()} and {@link Result#tables()}
 * are populated by {@link UnmarshalOptions#unmarshalFull} (the decode
 * path now records the side-channel directives the prior PRs only
 * skipped). Mirrors the Go-port test
 * {@code TestUnmarshalFull_RecordsDirectives} /
 * {@code TestUnmarshalFull_TablesAccessor}.
 */
class ResultAccessorsTest {

    @Test
    void recordsNamedDirectivesInOrder() {
        var b = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        var result = UnmarshalOptions.defaults().unmarshalFull("""
                @header chameleon.v1.LayerHeader {
                  id = "base"
                }
                @trace t.v1.Trace { sample = "0.1" }
                string_field = "body"
                """.getBytes(), b);

        assertEquals(2, result.directives().size());
        assertEquals("header", result.directives().get(0).name());
        assertEquals("chameleon.v1.LayerHeader", result.directives().get(0).type());
        assertNotNull(result.directives().get(0).body());
        assertTrue(new String(result.directives().get(0).body()).contains("id = \"base\""));
        assertEquals("trace", result.directives().get(1).name());

        // Body is decoded normally.
        var stringFd = AllTypes.getDescriptor().findFieldByName("string_field");
        assertEquals("body", b.build().getField(stringFd));
    }

    @Test
    void plainUnmarshalDoesNotRecord() {
        // Without trackPresence (plain unmarshal), directives are
        // consumed but not stored. No way to observe them — just verify
        // decoding succeeds and the body lands correctly.
        var b = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        UnmarshalOptions.defaults().unmarshal("""
                @header T { id = "x" }
                string_field = "body"
                """.getBytes(), b);
        var stringFd = AllTypes.getDescriptor().findFieldByName("string_field");
        assertEquals("body", b.build().getField(stringFd));
    }

    @Test
    void directivePrefixListExposed() {
        var b = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        var result = UnmarshalOptions.defaults().unmarshalFull("""
                @entry alice users.v1.User { name = "Alice" }
                string_field = "body"
                """.getBytes(), b);

        var d = result.directives().get(0);
        assertEquals("entry", d.name());
        assertEquals(java.util.List.of("alice", "users.v1.User"), d.prefixes());
        // Two prefixes ⇒ legacy `type` empty.
        assertEquals("", d.type());
    }

    @Test
    void zeroPrefixesAnonymousDirective() {
        var b = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        var result = UnmarshalOptions.defaults().unmarshalFull("""
                @entry { note = "metadata" }
                string_field = "body"
                """.getBytes(), b);

        var d = result.directives().get(0);
        assertEquals("entry", d.name());
        assertEquals(java.util.List.of(), d.prefixes());
        assertNotNull(d.body());
    }

    @Test
    void recordsTablesInOrder() {
        var b = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        var result = UnmarshalOptions.defaults().unmarshalFull("""
                @table events.v1.Created (id)
                ("e-1")
                ("e-2")
                @table events.v1.Deleted (id)
                ("e-9")
                """.getBytes(), b);

        assertEquals(2, result.tables().size());
        assertEquals("events.v1.Created", result.tables().get(0).type());
        assertEquals(2, result.tables().get(0).rows().size());
        assertEquals("events.v1.Deleted", result.tables().get(1).type());
        assertEquals(1, result.tables().get(1).rows().size());
    }

    @Test
    void tableCellStatesRoundTrip() {
        var b = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        var result = UnmarshalOptions.defaults().unmarshalFull("""
                @table t.T (a, b, c)
                ("x", 1, true)
                (null, , 3)
                (, "y", null)
                """.getBytes(), b);

        var rows = result.tables().get(0).rows();
        // Row 1: all present, distinct types.
        assertTrue(rows.get(0).cells().get(0) instanceof Ast.StringVal);
        assertTrue(rows.get(0).cells().get(1) instanceof Ast.IntVal);
        assertTrue(rows.get(0).cells().get(2) instanceof Ast.BoolVal);
        // Row 2: null / empty / present.
        assertTrue(rows.get(1).cells().get(0) instanceof Ast.NullVal);
        assertNull(rows.get(1).cells().get(1));
        assertTrue(rows.get(1).cells().get(2) instanceof Ast.IntVal);
        // Row 3: empty / present / null.
        assertNull(rows.get(2).cells().get(0));
        assertTrue(rows.get(2).cells().get(1) instanceof Ast.StringVal);
        assertTrue(rows.get(2).cells().get(2) instanceof Ast.NullVal);
    }

    @Test
    void allCellVariants() {
        // Smoke check that the new consumeAstValue switch covers every
        // PXF leaf value type that v1 cell-grammar permits.
        var b = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        var result = UnmarshalOptions.defaults().unmarshalFull("""
                @table t.T (s, i, f, b, by, ts, d, e, n)
                ("hi", 42, 3.14, true, b"aGVsbG8=", 2026-05-12T10:00:00Z, 1h30m, ENUM_VAL, null)
                """.getBytes(), b);
        var cells = result.tables().get(0).rows().get(0).cells();
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

    @Test
    void directivesAndTablesEmptyForBodyOnlyDocs() {
        var b = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        var result = UnmarshalOptions.defaults().unmarshalFull(
                "string_field = \"x\"".getBytes(), b);
        assertEquals(java.util.List.of(), result.directives());
        assertEquals(java.util.List.of(), result.tables());
    }
}
