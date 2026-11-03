// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe.it;

import org.junit.jupiter.api.Test;
import org.protowire.sbe.testproto.NewOrderSingle;
import org.protowire.sbe.testproto.NewOrderSingleSbeCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated codec hands per-call limits to the runtime (HARDENING.md
 * § Mandatory limits, #148): MaxMessageSize on the input, MaxRepeatedCount
 * on a group's declared count.
 */
final class SbeCodecLimitsTest {

    private static byte[] wire(int fills) {
        NewOrderSingle.Builder b = NewOrderSingle.newBuilder().setOrderId(1).setSymbol("AAPL");
        for (int i = 0; i < fills; i++) b.addFills(NewOrderSingle.Fill.newBuilder().setFillId(i));
        return NewOrderSingleSbeCodec.marshal(b.build());
    }

    @Test
    void maxMessageSizeIsTheCallers() {
        byte[] data = wire(3);
        assertEquals(3, NewOrderSingleSbeCodec.unmarshal(data, data.length, 3).getFillsCount());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> NewOrderSingleSbeCodec.unmarshal(data, data.length - 1, 3));
        assertTrue(e.getMessage().contains("exceeds MaxMessageSize=" + (data.length - 1)), e.getMessage());
    }

    @Test
    void maxRepeatedCountIsTheCallers() {
        byte[] data = wire(3);
        Exception e = assertThrows(RuntimeException.class,
            () -> NewOrderSingleSbeCodec.unmarshal(data, NewOrderSingle.newBuilder(), data.length, 2));
        assertTrue(e.getMessage().contains("MaxRepeatedCount=2"), e.getMessage());
    }
}
