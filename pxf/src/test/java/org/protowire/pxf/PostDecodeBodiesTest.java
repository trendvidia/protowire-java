// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Any;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.ElemHolder;
import org.protowire.pxf.testproto.ElemItem;
import org.protowire.pxf.testproto.MaskedRoot;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code (pxf.default)} and {@code (pxf.required)} apply in every message body
 * a document writes, on the full pipeline: the draft has no exception for an
 * assignment-form field, a list element, a map value, a keyed entry or an Any
 * body. Mirrors protowire-go's {@code element_defaults_test.go}.
 */
class PostDecodeBodiesTest {

    private static ElemHolder decode(String doc) {
        ElemHolder.Builder b = ElemHolder.newBuilder();
        new UnmarshalOptions(RESOLVER, false, false).unmarshalFull(doc.getBytes(StandardCharsets.UTF_8), b);
        return b.build();
    }

    private static final TypeResolver RESOLVER = url -> ElemItem.getDescriptor();

    @Test
    void theAssignmentFormIsTheBlockForm() {
        for (String doc : java.util.List.of("one { pick_i = 1 }", "one = { pick_i = 1 }")) {
            ElemItem one = decode(doc).getOne();
            assertEquals(1, one.getPickI(), doc + ": the default must not destroy the arm the document chose");
            assertEquals("item-dflt", one.getLabel(), doc);
        }
    }

    @Test
    void aFieldInsideABodyDoesNotCountAsTheParents() {
        // Without its own prefix, the element's root_label would mark the
        // root's as present, and the root's default would not apply.
        assertEquals("root-dflt", decode("one = { label = \"x\" }\nitems = [{ id = \"a\" }]").getRootLabel());
    }

    @Test
    void listElementsTakeTheirDefaults() {
        ElemHolder h = decode("items = [{ id = \"a\" }, { label = \"mine\" }]");
        assertEquals("item-dflt", h.getItems(0).getLabel());
        assertEquals("mine", h.getItems(1).getLabel());
    }

    @Test
    void listElementsRequireTheirFields() {
        PxfException e = assertThrows(PxfException.class, () -> decode("items = [{ deep { n = 1 } }, { deep { } }]"));
        assertTrue(e.getMessage().contains("required field \"items[1].deep.n\" is absent"), e.getMessage());
    }

    @Test
    void twoBindingsOfAListCountOneSequence() {
        PxfException e = assertThrows(PxfException.class, () -> decode("items = [{ }]\nitems = [{ deep { } }]"));
        assertTrue(e.getMessage().contains("items[1].deep.n"), e.getMessage());
    }

    @Test
    void mapValuesTakeTheirDefaults() {
        assertEquals("item-dflt", decode("by_key = { \"k\": { id = \"a\" } }").getByKeyOrThrow("k").getLabel());
        PxfException e = assertThrows(PxfException.class, () -> decode("by_key = { \"k\": { deep { } } }"));
        assertTrue(e.getMessage().contains("by_key[\"k\"].deep.n"), e.getMessage());
    }

    @Test
    void keyedEntriesTakeTheirDefaults() {
        ElemHolder h = decode("keyed { a { } b { label = \"mine\" } }");
        assertEquals("a", h.getKeyed(0).getId());
        assertEquals("item-dflt", h.getKeyed(0).getLabel());
        assertEquals("mine", h.getKeyed(1).getLabel());
    }

    @Test
    void anyBodiesTakeTheirDefaults() throws Exception {
        ElemHolder h = decode("payload { @type = \"x\"\n id = \"a\" }");
        Any any = h.getPayload();
        assertEquals("item-dflt", ElemItem.parseFrom(any.getValue()).getLabel());
    }

    @Test
    void presencePathsNameTheBody() {
        ElemHolder.Builder b = ElemHolder.newBuilder();
        Result r = Pxf.unmarshalFull("items = [{ id = \"a\" }]\none = { id = \"b\" }".getBytes(StandardCharsets.UTF_8), b);
        Set<String> present = r.presentFieldsView();
        assertTrue(present.contains("items[0].id"), present.toString());
        assertTrue(present.contains("one.id"), present.toString());
        assertFalse(present.contains("id"), present.toString());
    }

    /** The _null mask is the root's: a nested field that shares its number is an ordinary field. */
    @Test
    void theNullMaskIsSkippedAtTheRootOnly() {
        MaskedRoot.Builder b = MaskedRoot.newBuilder();
        Pxf.unmarshalFull("child { first = \"x\" }".getBytes(StandardCharsets.UTF_8), b);
        assertEquals("second-dflt", b.getChild().getSecond());
    }
}
