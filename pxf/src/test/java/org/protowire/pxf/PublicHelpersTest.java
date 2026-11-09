// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.FieldDescriptor;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.ElemHolder;
import org.protowire.pxf.testproto.KeyedBad;
import org.protowire.pxf.testproto.KeyedOk;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The helpers protowire-go exports that were package-private in Java (#210):
 * the {@code (pxf.key)} readers and {@link Result#presentFields()}. Each
 * expected value is protowire-go v1.17.0's {@code KeyFieldName},
 * {@code KeyField}, {@code IsKeyed} and {@code Result.PresentFields} on the
 * same schemas.
 */
class PublicHelpersTest {

    @Test
    void aWellPlacedKeyResolves() {
        FieldDescriptor items = KeyedOk.getDescriptor().findFieldByName("items");
        assertEquals("name", Pxf.keyFieldName(items));
        assertEquals("name", Pxf.keyField(items).getName());
        assertTrue(Pxf.isKeyed(items));
    }

    /** Go returns the authored name for each bad placement, and resolves none of them. */
    @Test
    void aBadPlacementKeepsItsNameAndResolvesToNothing() {
        String[][] bad = {{"single", "s"}, {"names", "s"}, {"missing", "nope"}, {"nonstring", "n"}};
        for (String[] b : bad) {
            FieldDescriptor fd = KeyedBad.getDescriptor().findFieldByName(b[0]);
            assertEquals(b[1], Pxf.keyFieldName(fd), b[0]);
            assertNull(Pxf.keyField(fd), b[0]);
            assertFalse(Pxf.isKeyed(fd), b[0]);
        }
        FieldDescriptor plain = ElemHolder.getDescriptor().findFieldByName("items");
        assertNull(Pxf.keyFieldName(plain));
        assertNull(Pxf.keyField(plain));
        assertFalse(Pxf.isKeyed(plain));
    }

    @Test
    void presentFieldsIsEveryPathTheDocumentSet() {
        Result r = Pxf.unmarshalFull("items = [{ id = \"a\" }]\none = { id = \"b\" }".getBytes(StandardCharsets.UTF_8),
            ElemHolder.newBuilder());
        assertEquals(Set.of("items", "items[0].id", "one", "one.id"), r.presentFields());

        // A null counts, as Go's PresentFields is the union of IsSet and IsNull.
        r = Pxf.unmarshalFull("keyed = [{ id = \"k\" }]\nby_key = { \"x\": { id = \"y\" } }\none = null"
            .getBytes(StandardCharsets.UTF_8), ElemHolder.newBuilder());
        assertEquals(Set.of("by_key", "by_key[\"x\"].id", "keyed", "keyed[0].id", "one"), r.presentFields());
        assertTrue(r.isNull("one"));
    }
}
