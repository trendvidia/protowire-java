// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe.it;

import org.junit.jupiter.api.Test;
import org.protowire.sbe.testproto.NewOrderSingle;
import org.protowire.sbe.testproto.NewOrderSingleSbeCodec;
import org.protowire.sbe.testproto.NewOrderSingleSbeMeta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The lite tier's half of #196, on a generated codec: unmarshal refuses a
 * header whose schema id is not the message's, since a template id is unique
 * only within a schema (protowire-go#257). The text is protowire-go's, as
 * sbe's {@code SbeHeaderTest} holds the full tier to it; both tiers decode
 * through sbe-runtime's {@code SbeWireCodec}.
 */
final class SbeHeaderLiteTest {

    @Test
    void anotherSchemasTemplateIsRefused() {
        byte[] wire = NewOrderSingleSbeCodec.marshal(NewOrderSingle.newBuilder().setOrderId(1).setSymbol("AAPL").build());
        assertEquals(NewOrderSingleSbeMeta.SCHEMA_ID, (wire[4] & 0xff) | (wire[5] & 0xff) << 8, "the header's schemaId");
        int other = NewOrderSingleSbeMeta.SCHEMA_ID + 1;
        wire[4] = (byte) other;
        wire[5] = (byte) (other >>> 8);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NewOrderSingleSbeCodec.unmarshal(wire));
        assertEquals("sbe: schema ID mismatch: got " + other + ", want " + NewOrderSingleSbeMeta.SCHEMA_ID
            + " for template " + NewOrderSingleSbeMeta.TEMPLATE.templateId, e.getMessage());
    }
}
