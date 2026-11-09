// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.dsw.Dsw;
import org.protowire.pxf.testproto.dswl.Dswl;
import org.protowire.pxf.testproto.dswnull.Dswnull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Pxf#appendDatasetHeader} and {@link Pxf#appendDatasetRow} write
 * what protowire-go's {@code AppendDatasetHeader} and {@code AppendDatasetRow}
 * write (#204), and a table they write reads back.
 *
 * <p>{@code dataset-write/cases.tsv} lists the cases: a message type from
 * protowire-go's own writer-test schemas ({@code dsw/v1/}), the columns, and
 * the message as a document. {@code dataset-write/go.tsv} is what
 * protowire-go v1.17.0 does with each, written by
 * {@code scripts/go-dataset-write/main.go}: the message's PB bytes, the
 * header, and the row, or the error for either. This test reads each
 * message from Go's bytes, so both ports write the same message, and holds
 * the header, the row or the error to Go's, word for word. Then it reads
 * each row Go writes back through {@link DatasetReader}, and holds the
 * message to the one Go's DatasetReader scans from it ({@code go.tsv}'s
 * last column): an empty cell reads back as the field's default, and a
 * field outside the columns too, so the two agree on what a table means,
 * not only on its text.
 */
class DatasetWriterTest {

    private static final FileDescriptor[] FILES = {Dsw.getDescriptor(), Dswnull.getDescriptor(), Dswl.getDescriptor()};

    private record Case(String type, List<String> columns, byte[] pb, String header, String row, byte[] back) {
        @Override public String toString() { return type + " " + columns; }
    }

    private static List<Case> cases() throws IOException {
        List<Case> out = new ArrayList<>();
        for (String line : resource("go.tsv").split("\n")) {
            String[] f = line.split("\t", -1);
            List<String> cols = f[1].isEmpty() ? List.of() : Arrays.asList(f[1].split(","));
            out.add(new Case(f[0], cols, HexFormat.of().parseHex(f[2]), unescape(f[3]), unescape(f[4]), HexFormat.of().parseHex(f[5])));
        }
        return out;
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = DatasetWriterTest.class.getResourceAsStream("/dataset-write/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** go.tsv writes a newline \n and a backslash \\. */
    private static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' ? '\n' : n);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static Descriptor type(String fullName) {
        for (FileDescriptor fd : FILES) {
            for (Descriptor d : fd.getMessageTypes()) if (d.getFullName().equals(fullName)) return d;
        }
        throw new AssertionError("no type " + fullName);
    }

    /** {@code text}, or the error it is refused with: "ERR " and the message without its position, as go.tsv writes it. */
    private static String attempt(java.util.function.Supplier<StringBuilder> write) {
        try {
            return write.get().toString();
        } catch (PxfException e) {
            String m = e.getMessage();
            String lead = e.position() + ": ";
            return "ERR " + (m.startsWith(lead) ? m.substring(lead.length()) : m);
        }
    }

    @Test
    void eachCaseIsWrittenAsGoWritesIt() throws Exception {
        List<Case> cases = cases();
        assertEquals(48, cases.size(), "go.tsv's cases");
        for (Case c : cases) {
            Message msg = DynamicMessage.parseFrom(type(c.type()), c.pb());
            assertEquals(c.header(), attempt(() -> Pxf.appendDatasetHeader(new StringBuilder(), type(c.type()), c.columns())), c.toString());
            assertEquals(c.row(), attempt(() -> Pxf.appendDatasetRow(new StringBuilder(), msg, c.columns())), c.toString());
        }
    }

    @Test
    void eachRowGoWritesReadsBackAsGoReadsIt() throws Exception {
        int read = 0;
        for (Case c : cases()) {
            if (c.header().startsWith("ERR") || c.row().startsWith("ERR")) continue;
            DatasetReader r = new DatasetReader(new ByteArrayInputStream((c.header() + c.row()).getBytes(StandardCharsets.UTF_8)));
            DynamicMessage.Builder b = DynamicMessage.newBuilder(type(c.type()));
            assertTrue(r.scan(b), c.toString());
            assertEquals(DynamicMessage.parseFrom(type(c.type()), c.back()), b.build(), c.toString());
            assertFalse(r.scan(DynamicMessage.newBuilder(type(c.type()))), c.toString());
            read++;
        }
        assertEquals(33, read, "rows Go writes");
    }

    @Test
    void anErrorLeavesTheBufferAsItWas() {
        StringBuilder dst = new StringBuilder("kept\n");
        assertThrows(PxfException.class, () -> Pxf.appendDatasetHeader(dst, type("dsw.v1.Req"), List.of("id")));
        assertThrows(PxfException.class,
            () -> Pxf.appendDatasetRow(dst, DynamicMessage.newBuilder(type("dsw.v1.Req")).build(), List.of("id", "name")));
        assertEquals("kept\n", dst.toString());
    }
}
