// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import keyed.v1.Keyed.Catalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import test.v1.binding.KeyedEntryBinding.Holder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An entry name binds the key field (draft -01 §3.13, protowire#480), so a
 * named entry's body can't undo it: {@code null} on the key, or any binding
 * of another member of the key's oneof, is a key conflict. The fixtures are
 * vendored from protowire#480's {@code testdata/keyed/}. Both decode paths
 * run each one.
 */
class KeyedEntryBindingTest {

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = KeyedEntryBindingTest.class.getResourceAsStream("/keyed/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return in.readAllBytes();
        }
    }

    private static void rejectedBothWays(byte[] doc, com.google.protobuf.Descriptors.Descriptor d, String message) {
        PxfException plain = assertThrows(PxfException.class, () -> Pxf.unmarshal(doc, d));
        PxfException full = assertThrows(PxfException.class, () -> Pxf.unmarshalFull(doc, DynamicMessage.newBuilder(d)));
        assertTrue(plain.getMessage().contains(message), plain.getMessage());
        assertTrue(full.getMessage().contains(message), full.getMessage());
    }

    private static Catalog catalog(String fixture) throws IOException, InvalidProtocolBufferException {
        byte[] doc = fixture(fixture);
        Catalog fromFull = Catalog.parseFrom(fullBytes(doc));
        Catalog plain = Catalog.parseFrom(Pxf.unmarshal(doc, Catalog.getDescriptor()).toByteArray());
        assertEquals(plain, fromFull);
        return plain;
    }

    private static byte[] fullBytes(byte[] doc) {
        DynamicMessage.Builder b = DynamicMessage.newBuilder(Catalog.getDescriptor());
        Pxf.unmarshalFull(doc, b);
        return b.build().toByteArray();
    }

    private static Holder holder(String doc) throws InvalidProtocolBufferException {
        return Holder.parseFrom(Pxf.unmarshal(doc.getBytes(StandardCharsets.UTF_8), Holder.getDescriptor()).toByteArray());
    }

    @Test
    void nullOnTheKeyConflicts() throws IOException {
        rejectedBothWays(fixture("err-key-null.pxf"), Catalog.getDescriptor(),
                "null on key field \"name\" conflicts with entry name \"alpha\" in keyed field \"tags\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"err-key-oneof-sibling.pxf", "err-key-oneof-sibling-null.pxf"})
    void aOneofSiblingConflicts(String fixture) throws IOException {
        rejectedBothWays(fixture(fixture), Catalog.getDescriptor(),
                "field \"other\" shares oneof \"pick\" with key field \"name\", which entry name \"alpha\" already set in keyed field \"choices\"");
    }

    @Test
    void aKeyInAOneofTakesTheEntryName() throws Exception {
        Catalog c = catalog("key-oneof-ok.pxf");
        assertEquals(3, c.getChoicesCount());
        assertEquals("alpha", c.getChoices(0).getName());
        assertEquals("beta", c.getChoices(1).getName());
        assertEquals("x", c.getChoices(2).getOther());
    }

    @Test
    void anAnonymousElementMayNullItsKey() throws Exception {
        Catalog c = catalog("key-null-anonymous-ok.pxf");
        assertEquals(1, c.getTagsCount());
        assertFalse(c.getTags(0).hasName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"slots { a { inner { } } }", "slots { a { inner = { } } }", "slots { a { inner = null } }"})
    void aMessageSiblingConflictsInEverySpelling(String doc) {
        rejectedBothWays(doc.getBytes(StandardCharsets.UTF_8), Holder.getDescriptor(),
                "field \"inner\" shares oneof \"pick\" with key field \"name\"");
    }

    @Test
    void anAnonymousElementMayChooseASibling() throws Exception {
        assertTrue(holder("slots = [ { inner { } } ]").getSlots(0).hasInner());
    }

    @Test
    void aNestedBodyIsNotCheckedAgainstTheEntry() throws Exception {
        Holder h = holder("slots { a { extra { name = null } } }");
        assertEquals("a", h.getSlots(0).getName());
        assertFalse(h.getSlots(0).getExtra().hasName());
    }

    @Test
    void aMarshalledAnonymousElementDecodesBack() throws Exception {
        // Marshallers write an element with no key in the anonymous form, so
        // the new rejections can't reach anything this port writes.
        Catalog c = catalog("key-oneof-ok.pxf");
        byte[] out = Pxf.marshal((Message) c);
        assertEquals(c, Catalog.parseFrom(Pxf.unmarshal(out, Catalog.getDescriptor()).toByteArray()));
    }

    @Test
    void fmtKeepsTheElementsItCannotName() throws Exception {
        for (String name : new String[]{"key-oneof-ok.pxf", "key-null-anonymous-ok.pxf"}) {
            byte[] doc = fixture(name);
            byte[] formatted = Pxf.formatDocument(Parser.parse(doc), Catalog.getDescriptor());
            assertEquals(Catalog.parseFrom(Pxf.unmarshal(doc, Catalog.getDescriptor()).toByteArray()),
                    Catalog.parseFrom(Pxf.unmarshal(formatted, Catalog.getDescriptor()).toByteArray()),
                    new String(formatted, StandardCharsets.UTF_8));
        }
    }
}
