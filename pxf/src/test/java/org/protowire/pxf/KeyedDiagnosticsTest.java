// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keyed repeated fields' errors (draft -01 §3.13) come back as protowire-go
 * gives them (#208): the decoder throws a {@link KeyedException} of Go's
 * {@code KeyedError} kind, at its position, and
 * {@link Pxf#keyedDiagnostics} returns every one Go's
 * {@code KeyedDiagnostics} returns. {@code keyed-diagnostics/go.tsv} is Go's,
 * written by {@code scripts/go-keyed-diagnostics/main.go} from
 * {@code keyed-diagnostics/cases.tsv}: every document of the spec corpus's
 * {@code keyed/}, Go's own key-binding test documents, and two that hold
 * every kind more than once. The conformance job reruns it against
 * protowire-go main.
 */
class KeyedDiagnosticsTest {

    private static final Map<String, Descriptor> TYPES = Map.of(
            "keyed.v1.Node", keyed.v1.Keyed.Node.getDescriptor(),
            "keyed.v1.Doc", keyed.v1.Keyed.Doc.getDescriptor(),
            "keyed.v1.Deployment", keyed.v1.Keyed.Deployment.getDescriptor(),
            "keyed.v1.Catalog", keyed.v1.Keyed.Catalog.getDescriptor(),
            "undo.v1.P", org.protowire.pxf.testproto.undo.Undo.P.getDescriptor());

    private record Case(String name, String type, byte[] doc) {}

    private static List<String> lines(String resource) throws IOException {
        try (InputStream in = KeyedDiagnosticsTest.class.getResourceAsStream(resource)) {
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
        }
    }

    private static String keyed(KeyedException e) {
        Position p = e.position();
        return e.kind() + " " + p.line() + ":" + p.column() + ":" + p.offset() + " field=" + Token.quote(e.field())
                + " key=" + Token.quote(e.key()) + " " + e.detail();
    }

    private static String decode(Case c) {
        try {
            Pxf.unmarshalFull(c.doc(), DynamicMessage.newBuilder(type(c)));
            return "ok";
        } catch (KeyedException e) {
            return "keyed " + keyed(e);
        } catch (PxfException e) {
            return "error " + e.getMessage();
        }
    }

    private static String diagnostics(Case c) {
        Parser.TolerantResult t = Parser.parseTolerant(c.doc());
        List<String> out = new ArrayList<>();
        for (PxfException e : t.errors()) out.add("syntax " + e.getMessage());
        for (KeyedException e : Pxf.keyedDiagnostics(t.document(), type(c))) out.add(keyed(e));
        return String.join(" | ", out);
    }

    private static Descriptor type(Case c) {
        Descriptor d = TYPES.get(c.type());
        if (d == null) throw new AssertionError("no descriptor for " + c.type());
        return d;
    }

    @Test
    void eachDocumentGetsGosKeyedErrors() throws IOException {
        List<Case> cases = new ArrayList<>();
        for (String l : lines("/keyed-diagnostics/cases.tsv")) {
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] f = l.split("\t", -1);
            cases.add(new Case(f[0], f[1], Base64.getDecoder().decode(f[2])));
        }
        List<String> go = lines("/keyed-diagnostics/go.tsv");
        assertEquals(cases.size(), go.size());
        assertEquals(39, go.size());
        List<String> differ = new ArrayList<>();
        int kinds = 0;
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            String[] g = go.get(i).split("\t", -1);
            assertEquals(c.name(), g[0]);
            String dec = decode(c), diags = diagnostics(c);
            if (!dec.equals(g[1])) differ.add(c.name() + "\n  go decode:   " + g[1] + "\n  java decode: " + dec);
            if (!diags.equals(g[2])) differ.add(c.name() + "\n  go diagnostics:   " + g[2] + "\n  java diagnostics: " + diags);
            if (g[1].startsWith("keyed ")) kinds++;
        }
        assertTrue(differ.isEmpty(), differ.size() + " differ from Go:\n" + differ.stream().limit(10).collect(Collectors.joining("\n")));
        assertEquals(20, kinds, "documents Go refuses with a KeyedError");
    }

    /** Every kind is one the decoder throws, so a caller can tell them apart. */
    @Test
    void theDecoderThrowsEachKind() {
        Descriptor p = org.protowire.pxf.testproto.undo.Undo.P.getDescriptor();
        for (Map.Entry<String, KeyedException.Kind> e : Map.of(
                "ko { a { } a { } }", KeyedException.Kind.DUPLICATE_KEY,
                "ko { a { name = \"b\" } }", KeyedException.Kind.KEY_CONFLICT,
                "ko { \"\" { } }", KeyedException.Kind.EMPTY_KEY,
                "\"q\" = 1", KeyedException.Kind.QUOTED_NAME_UNKEYED).entrySet()) {
            try {
                Pxf.unmarshalFull(e.getKey().getBytes(StandardCharsets.UTF_8), DynamicMessage.newBuilder(p));
                throw new AssertionError("accepted " + e.getKey());
            } catch (KeyedException k) {
                assertEquals(e.getValue(), k.kind(), e.getKey());
            }
        }
    }
}
