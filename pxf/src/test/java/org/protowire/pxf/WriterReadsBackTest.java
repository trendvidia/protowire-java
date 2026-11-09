// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.FieldMask;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.MaskedChild;
import org.protowire.pxf.testproto.MaskedRoot;
import org.protowire.pxf.testproto.anyback.RequiredOptional;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What marshal writes, unmarshalFull reads back as the same message, or
 * marshal refuses it (#187; protowire-go#259, #263, #268). The text gate's
 * Annotated cases hold the written shapes to protowire-go's goldens; these
 * are the two edges they do not reach.
 */
class WriterReadsBackTest {

    /** A _null mask naming a field that cannot hold null: written as its value, since null there is a decode error. */
    @Test
    void aMaskNamingAPlainScalarWritesItsValue() {
        MaskedRoot m = MaskedRoot.newBuilder()
                .setChild(MaskedChild.newBuilder().setFirst("a"))
                .setNull(FieldMask.newBuilder().addPaths("child.first"))
                .build();
        String text = new String(Pxf.marshal(m), StandardCharsets.UTF_8);
        assertTrue(text.contains("first = \"a\""), text);
        MaskedRoot.Builder back = MaskedRoot.newBuilder();
        Pxf.unmarshalFull(text.getBytes(StandardCharsets.UTF_8), back);
        assertEquals("a", back.getChild().getFirst());
    }

    /**
     * An unset required field with presence has no text: a reader refuses it
     * absent and refuses it null. protowire-go's error, word for word.
     */
    @Test
    void anUnsetRequiredFieldWithPresenceIsRefused() {
        PxfException e = assertThrows(PxfException.class,
                () -> Pxf.marshal(RequiredOptional.newBuilder().setS("s").build()));
        assertEquals("1:1: pxf: anyback.v1.RequiredOptional.n is unset, and the field is (pxf.required)", e.getMessage());
    }
}
