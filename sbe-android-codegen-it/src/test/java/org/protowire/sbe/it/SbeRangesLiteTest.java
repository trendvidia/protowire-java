// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe.it;

import org.junit.jupiter.api.Test;
import org.protowire.sbe.FieldTemplate;
import org.protowire.sbe.MessageTemplate;
import org.protowire.sbe.ViewSchema;
import org.protowire.sbe.testproto.NewOrderSingle;
import org.protowire.sbe.testproto.NewOrderSingleSbeCodec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The lite tier's half of #197 and #198: its generated codecs write through
 * sbe-runtime's {@code SbeWireCodec}, so a value the primitive cannot hold is
 * refused there too, and a {@code MessageTemplate} checks itself when it is
 * built, so a generated template the 16-bit header cannot describe fails as
 * the full tier's {@code Codec.of} does (decided on #197: the check lives in
 * :sbe-runtime). Generated templates carry the short field name until the
 * plugin passes the full one, so the texts name the field by it.
 */
final class SbeRangesLiteTest {

    /** {@code side} is a uint32 field written as uint8. */
    @Test
    void aValueThePrimitiveCannotHoldIsNotWritten() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NewOrderSingleSbeCodec.marshal(
            NewOrderSingle.newBuilder().setOrderId(1).setSymbol("AAPL").setSide(300).build()));
        assertEquals("sbe: field side: value 300 is outside the range of uint8, [0, 255]", e.getMessage());
    }

    private static MessageTemplate template(int templateId, int schemaId, int version, FieldTemplate... fields) {
        return new MessageTemplate(templateId, schemaId, version, 0, List.of(fields), List.of(), new ViewSchema());
    }

    @Test
    void aTemplateTheHeaderCannotDescribeIsRefused() {
        assertEquals("sbe: (sbe.template_id) = 65537 is past 65535, the largest templateId SBE can state",
            assertThrows(IllegalArgumentException.class, () -> template(65537, 1, 0)).getMessage());
        assertEquals("sbe: (sbe.schema_id) = 65537 is past 65535, the largest schemaId SBE can state",
            assertThrows(IllegalArgumentException.class, () -> template(1, 65537, 0)).getMessage());
        assertEquals("sbe: (sbe.version) = 65538 is past 65535, the largest version SBE can state",
            assertThrows(IllegalArgumentException.class, () -> template(1, 1, 65538)).getMessage());
        assertEquals("sbe: field s: (sbe.length) = 65537 is past 65535, the largest blockLength SBE can state",
            assertThrows(IllegalArgumentException.class,
                () -> template(1, 1, 0, new FieldTemplate(1, "s", 9, 0, 65537, "char", null))).getMessage());
        assertEquals("sbe: field b: ends at byte 80000 of its block, past 65535, the largest blockLength SBE can state",
            assertThrows(IllegalArgumentException.class, () -> template(1, 1, 0,
                new FieldTemplate(1, "a", 9, 0, 40000, "char", null),
                new FieldTemplate(2, "b", 9, 40000, 40000, "char", null))).getMessage());
        // 65535 is the largest each can state.
        template(65535, 65535, 65535, new FieldTemplate(1, "s", 9, 0, 65535, "char", null));
    }

    @Test
    void anEncodingTheFieldCannotTakeIsRefused() {
        assertEquals("sbe: field s: (sbe.encoding) = \"int32\" on a string field; it narrows a numeric field, and a string field"
                + " takes (sbe.length)",
            assertThrows(IllegalArgumentException.class,
                () -> template(1, 1, 0, new FieldTemplate(1, "s", 9, 0, 4, "int32", null))).getMessage());
        assertEquals("sbe: field n: (sbe.encoding) = \"float\" is a floating-point primitive and the field is int32",
            assertThrows(IllegalArgumentException.class,
                () -> template(1, 1, 0, new FieldTemplate(1, "n", 5, 0, 4, "float", null))).getMessage());
        assertEquals("sbe: field d: (sbe.encoding) = \"int32\" is an integer primitive and the field is double",
            assertThrows(IllegalArgumentException.class,
                () -> template(1, 1, 0, new FieldTemplate(1, "d", 1, 0, 4, "int32", null))).getMessage());
    }
}
