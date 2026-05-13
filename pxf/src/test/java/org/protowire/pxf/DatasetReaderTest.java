// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.AllTypes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Streaming {@code @dataset} consumption tests. Mirrors protowire-go's
 * table_stream_test.go.
 */
class DatasetReaderTest {

    private static InputStream s(String in) {
        return new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8));
    }

    // --- Happy path ---

    @Test
    void basicStreaming() throws IOException {
        var tr = new DatasetReader(s("""
                @dataset trades.v1.Trade (symbol, price, qty)
                ("AAPL", 192.34, 100)
                ("MSFT", 410.10, 50)
                ("GOOG", 142.00, 25)"""));
        assertEquals("trades.v1.Trade", tr.type());
        assertEquals(List.of("symbol", "price", "qty"), tr.columns());
        assertEquals(List.of(), tr.directives());

        List<Ast.DatasetRow> rows = new ArrayList<>();
        for (Ast.DatasetRow r; (r = tr.next()) != null; ) rows.add(r);
        assertEquals(3, rows.size());
        var sv = (Ast.StringVal) rows.get(0).cells().get(0);
        assertEquals("AAPL", sv.value());
    }

    @Test
    void emptyTableReturnsNullImmediately() throws IOException {
        var tr = new DatasetReader(s("@dataset trades.v1.Trade (symbol, price)"));
        assertNull(tr.next());
        assertNull(tr.next()); // sticky
    }

    // --- Three cell states ---

    @Test
    void cellStates() throws IOException {
        var tr = new DatasetReader(s("""
                @dataset t.T (a, b, c)
                ("x", 1, true)
                (null, , 3)
                (, "y", null)"""));

        var r1 = tr.next();
        assertNotNull(r1);
        assertInstanceOf(Ast.StringVal.class, r1.cells().get(0));
        assertInstanceOf(Ast.IntVal.class,    r1.cells().get(1));
        assertInstanceOf(Ast.BoolVal.class,   r1.cells().get(2));

        var r2 = tr.next();
        assertInstanceOf(Ast.NullVal.class, r2.cells().get(0));
        assertNull(r2.cells().get(1));
        assertInstanceOf(Ast.IntVal.class, r2.cells().get(2));

        var r3 = tr.next();
        assertNull(r3.cells().get(0));
        assertInstanceOf(Ast.StringVal.class, r3.cells().get(1));
        assertInstanceOf(Ast.NullVal.class,   r3.cells().get(2));

        assertNull(tr.next());
    }

    // --- Leading directives ---

    @Test
    void sideChannelDirectivesBeforeHeader() throws IOException {
        var tr = new DatasetReader(s("""
                @header meta.v1.H { generated_at = 2026-05-12T10:00:00Z }
                @dataset trades.v1.Trade (symbol)
                ("AAPL")
                ("MSFT")"""));

        assertEquals(1, tr.directives().size());
        assertEquals("header", tr.directives().get(0).name());
        assertEquals("meta.v1.H", tr.directives().get(0).type());

        int count = 0;
        while (tr.next() != null) count++;
        assertEquals(2, count);
    }

    // --- Standalone constraint enforced at header read ---

    @Test
    void rejectsAtTypeWithAtTable() {
        var ex = assertThrows(PxfException.class, () ->
                new DatasetReader(s("""
                        @type some.Other
                        @dataset trades.v1.Trade (symbol)
                        ("AAPL")""")));
        assertTrue(ex.getMessage().contains("@type"));
    }

    // --- No @dataset ---

    @Test
    void noTableInStream() {
        assertThrows(NoSuchElementException.class, () ->
                new DatasetReader(s("string_field = \"x\"")));
    }

    @Test
    void emptyInput() {
        assertThrows(NoSuchElementException.class, () -> new DatasetReader(s("")));
    }

    // --- Errors mid-stream are sticky ---

    @Test
    void errorsAreSticky() throws IOException {
        var tr = new DatasetReader(s("""
                @dataset T (a, b, c)
                ("x", 1, 2)
                ("y", 1)""")); // arity mismatch
        assertNotNull(tr.next());
        var ex = assertThrows(PxfException.class, tr::next);
        // Subsequent calls rethrow the same sticky error.
        var ex2 = assertThrows(PxfException.class, tr::next);
        assertEquals(ex.getMessage(), ex2.getMessage());
    }

    @Test
    void rejectsListCellMidStream() throws IOException {
        var tr = new DatasetReader(s("""
                @dataset T (a, b)
                ("ok", 1)
                ("bad", [1, 2])"""));
        assertNotNull(tr.next());
        var ex = assertThrows(PxfException.class, tr::next);
        assertTrue(ex.getMessage().contains("list values"));
    }

    // --- Strings / comments inside cells don't trip row scanner ---

    @Test
    void stringWithParens() throws IOException {
        var tr = new DatasetReader(s("""
                @dataset T (note, n)
                ("contains (paren) inside", 1)
                ("normal", 2)"""));
        var r1 = tr.next();
        assertEquals("contains (paren) inside", ((Ast.StringVal) r1.cells().get(0)).value());
        var r2 = tr.next();
        assertEquals("normal", ((Ast.StringVal) r2.cells().get(0)).value());
    }

    @Test
    void blockCommentBetweenRows() throws IOException {
        var tr = new DatasetReader(s("""
                @dataset T (a)
                ("x")
                /* this comment ) has ( parens
                   spanning multiple lines */
                ("y")"""));
        int count = 0;
        while (tr.next() != null) count++;
        assertEquals(2, count);
    }

    @Test
    void lineCommentBetweenRows() throws IOException {
        var tr = new DatasetReader(s("""
                @dataset T (a)
                ("x")
                # this is a comment, with ( a paren ) inside
                ("y")
                // another comment ) here
                ("z")"""));
        int count = 0;
        while (tr.next() != null) count++;
        assertEquals(3, count);
    }

    // --- Chunked InputStream: rows split across reads ---

    private static class ChunkedStream extends InputStream {
        private final byte[] data;
        private int i = 0;
        ChunkedStream(byte[] data) { this.data = data; }

        @Override
        public int read() {
            if (i >= data.length) return -1;
            return data[i++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (i >= data.length) return -1;
            b[off] = data[i++];
            return 1; // one byte at a time — adversarial for any buffering bug
        }
    }

    @Test
    void handlesByteAtATimeReader() throws IOException {
        var tr = new DatasetReader(new ChunkedStream("""
                @dataset T (a, b, c)
                ("hello", 42, true)
                ("world", 99, false)
                ("end", 0, null)""".getBytes(StandardCharsets.UTF_8)));
        int count = 0;
        while (tr.next() != null) count++;
        assertEquals(3, count);
    }

    // --- Multi-table via tail() ---

    @Test
    void multipleTablesViaTail() throws IOException {
        var tr1 = new DatasetReader(s("""
                @dataset events.v1.Created (id, ts)
                ("e-1", 2026-05-12T10:00:00Z)
                ("e-2", 2026-05-12T10:00:01Z)
                @dataset events.v1.Deleted (id, ts)
                ("e-9", 2026-05-12T10:00:02Z)"""));
        assertEquals("events.v1.Created", tr1.type());
        int c1 = 0;
        while (tr1.next() != null) c1++;
        assertEquals(2, c1);

        var tr2 = new DatasetReader(tr1.tail());
        assertEquals("events.v1.Deleted", tr2.type());
        int c2 = 0;
        while (tr2.next() != null) c2++;
        assertEquals(1, c2);
    }

    // --- Streaming and materializing produce equivalent rows ---

    @Test
    void equivalentToMaterializingPath() throws IOException {
        String in = """
                @dataset t.T (a, b, c)
                ("alpha", 1, true)
                ("beta", null, false)
                (, , )
                ("gamma", 99, true)""";
        // Materializing.
        var doc = Parser.parse(in);
        assertEquals(1, doc.datasets().size());
        var mat = doc.datasets().get(0).rows();

        // Streaming.
        var tr = new DatasetReader(s(in));
        List<Ast.DatasetRow> stream = new ArrayList<>();
        for (Ast.DatasetRow r; (r = tr.next()) != null; ) stream.add(r);

        assertEquals(mat.size(), stream.size());
        for (int i = 0; i < mat.size(); i++) {
            assertEquals(mat.get(i).cells().size(), stream.get(i).cells().size());
            for (int j = 0; j < mat.get(i).cells().size(); j++) {
                var m = mat.get(i).cells().get(j);
                var sCell = stream.get(i).cells().get(j);
                if (m == null) assertNull(sCell);
                else {
                    assertNotNull(sCell);
                    assertEquals(m.getClass(), sCell.getClass());
                }
            }
        }
    }

    // --- Header size limit ---

    @Test
    void rejectsOversizedHeader() {
        // 70 KiB identifier > 64 KiB cap.
        String long_ = "a".repeat(70 * 1024);
        var ex = assertThrows(PxfException.class, () ->
                new DatasetReader(s("@dataset " + long_ + ".T (col)\n(1)")));
        assertTrue(ex.getMessage().contains("header exceeds"));
    }

    // --- scan(Message.Builder) ---

    @Test
    void scanHappyPath() throws IOException {
        var tr = new DatasetReader(s("""
                @dataset test.v1.AllTypes (string_field, int32_field, bool_field, enum_field)
                ("alpha", 1, true, STATUS_ACTIVE)
                ("beta", 2, false, STATUS_INACTIVE)
                ("gamma", 3, true, STATUS_UNSPECIFIED)"""));
        var desc = AllTypes.getDescriptor();
        int count = 0;
        while (true) {
            var b = DynamicMessage.newBuilder(desc);
            if (!tr.scan(b)) break;
            count++;
        }
        assertEquals(3, count);
    }

    @Test
    void scanReturnsFalseOnEof() throws IOException {
        var tr = new DatasetReader(s("""
                @dataset test.v1.AllTypes (string_field)
                ("x")"""));
        var b1 = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        assertTrue(tr.scan(b1));
        var b2 = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        assertFalse(tr.scan(b2));
    }

    @Test
    void scanEmptyCellLeavesFieldUnset() throws IOException {
        var tr = new DatasetReader(s("""
                @dataset test.v1.AllTypes (string_field, int32_field)
                ("present", 7)
                (, 99)
                ("set", )"""));
        var stringFd = AllTypes.getDescriptor().findFieldByName("string_field");
        var intFd = AllTypes.getDescriptor().findFieldByName("int32_field");

        var b1 = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        tr.scan(b1);
        assertEquals("present", b1.build().getField(stringFd));
        assertEquals(7, b1.build().getField(intFd));

        var b2 = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        tr.scan(b2);
        assertEquals("", b2.build().getField(stringFd));
        assertEquals(99, b2.build().getField(intFd));

        var b3 = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        tr.scan(b3);
        assertEquals("set", b3.build().getField(stringFd));
        assertEquals(0, b3.build().getField(intFd));
    }

    @Test
    void scanNullOnWrapperClears() throws IOException {
        var tr = new DatasetReader(s("""
                @dataset test.v1.AllTypes (string_field, nullable_int)
                ("with-value", 42)
                ("nullified", null)"""));
        var nullableIntFd = AllTypes.getDescriptor().findFieldByName("nullable_int");

        var b1 = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        tr.scan(b1);
        assertTrue(b1.build().hasField(nullableIntFd));

        var b2 = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        tr.scan(b2);
        assertFalse(b2.build().hasField(nullableIntFd));
    }

    @Test
    void scanWellKnownTimestamp() throws IOException {
        var tr = new DatasetReader(s("""
                @dataset test.v1.AllTypes (string_field, ts_field)
                ("first", 2026-05-12T10:30:00Z)"""));
        var tsFd = AllTypes.getDescriptor().findFieldByName("ts_field");

        var b = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        tr.scan(b);
        var tsMsg = (com.google.protobuf.Message) b.build().getField(tsFd);
        var secondsFd = tsMsg.getDescriptorForType().findFieldByName("seconds");
        long expected = java.time.Instant.parse("2026-05-12T10:30:00Z").getEpochSecond();
        assertEquals(expected, tsMsg.getField(secondsFd));
    }

    // --- BindRow against the materializing path ---

    @Test
    void bindRowAgainstMaterializingPath() {
        var doc = Parser.parse("""
                @dataset test.v1.AllTypes (string_field, int32_field)
                ("alpha", 1)
                ("beta", 2)""");
        var tbl = doc.datasets().get(0);
        for (int i = 0; i < tbl.rows().size(); i++) {
            var b = DynamicMessage.newBuilder(AllTypes.getDescriptor());
            BindRow.bindRow(b, tbl.columns(), tbl.rows().get(i));
        }
    }

    @Test
    void bindRowArityMismatch() {
        var b = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        var row = new Ast.DatasetRow(Position.UNKNOWN,
                java.util.Collections.singletonList(new Ast.StringVal(Position.UNKNOWN, "x")));
        var ex = assertThrows(IllegalArgumentException.class,
                () -> BindRow.bindRow(b, List.of("a", "b"), row));
        assertTrue(ex.getMessage().contains("columns vs"));
    }

    @Test
    void bindRowRejectsNonLeafCell() {
        // Hand-construct a row with a ListVal cell — the parser rejects
        // these earlier, but a caller that builds a DatasetRow manually
        // bypasses that check.
        var b = DynamicMessage.newBuilder(AllTypes.getDescriptor());
        var row = new Ast.DatasetRow(Position.UNKNOWN,
                java.util.Collections.singletonList(
                        new Ast.ListVal(Position.UNKNOWN,
                                List.of(new Ast.StringVal(Position.UNKNOWN, "x")))));
        var ex = assertThrows(IllegalArgumentException.class,
                () -> BindRow.bindRow(b, List.of("string_field"), row));
        assertTrue(ex.getMessage().contains("scalar-shaped"));
    }
}
