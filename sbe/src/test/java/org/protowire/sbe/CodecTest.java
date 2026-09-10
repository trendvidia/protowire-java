// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import org.protowire.sbe.testproto.NewOrderSingle;
import org.protowire.sbe.testproto.NewOrderSingle.Fill;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodecTest {

    @Test
    void roundTripWithGroup() {
        Codec codec = Codec.of(NewOrderSingle.getDescriptor().getFile());

        NewOrderSingle msg = NewOrderSingle.newBuilder()
                .setOrderId(123_456L)
                .setSymbol("AAPL")
                .setPrice(15000)
                .setQuantity(100)
                .setSide(1)
                .addFills(Fill.newBuilder().setFillPrice(15001).setFillQty(50).setFillId(1).build())
                .addFills(Fill.newBuilder().setFillPrice(15002).setFillQty(50).setFillId(2).build())
                .build();

        byte[] data = codec.marshal(msg);
        NewOrderSingle.Builder back = NewOrderSingle.newBuilder();
        codec.unmarshal(data, back);
        NewOrderSingle decoded = back.build();

        assertEquals(123_456L, decoded.getOrderId());
        assertEquals("AAPL", decoded.getSymbol());
        assertEquals(15000L, decoded.getPrice());
        assertEquals(100, decoded.getQuantity());
        assertEquals(1, decoded.getSide());
        assertEquals(2, decoded.getFillsCount());
        assertEquals(15001L, decoded.getFills(0).getFillPrice());
        assertEquals(2L, decoded.getFills(1).getFillId());
    }

    @Test
    void zeroAllocView() {
        Codec codec = Codec.of(NewOrderSingle.getDescriptor().getFile());

        NewOrderSingle msg = NewOrderSingle.newBuilder()
                .setOrderId(99L)
                .setSymbol("MSFT")
                .setPrice(40000)
                .setQuantity(5)
                .setSide(2)
                .addFills(Fill.newBuilder().setFillPrice(40001).setFillQty(5).setFillId(7).build())
                .build();
        byte[] data = codec.marshal(msg);
        View v = codec.view(data);

        assertEquals(99L, v.uintValue("order_id"));
        assertEquals("MSFT", v.stringValue("symbol"));
        assertEquals(40000L, v.intValue("price"));
        assertEquals(2L, v.uintValue("side"));

        View.GroupView fills = v.group("fills");
        assertEquals(1, fills.size());
        assertEquals(7L, fills.entry(0).uintValue("fill_id"));
    }

    // -- HARDENING.md § SBE and § Mandatory limits (#79) -----------------------

    private static NewOrderSingle withFills(int n) {
        NewOrderSingle.Builder b = NewOrderSingle.newBuilder().setOrderId(1).setSymbol("AAPL").setPrice(1).setQuantity(1).setSide(1);
        for (int i = 0; i < n; i++) b.addFills(Fill.newBuilder().setFillPrice(i).setFillQty(1).setFillId(i).build());
        return b.build();
    }

    private static IllegalArgumentException reject(Codec codec, byte[] data, String needle) {
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> codec.unmarshal(data, NewOrderSingle.newBuilder()));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains(needle), e.getMessage());
        return e;
    }

    @Test
    void groupCountIsBoundedBeforeAnyEntryIsAllocated() {
        Codec codec = Codec.of(NewOrderSingle.getDescriptor().getFile());
        byte[] data = codec.marshal(withFills(16));
        reject(codec.withLimits(codec.maxMessageSize(), 8), data, "group fills declares 16 entries, MaxRepeatedCount=8");
        NewOrderSingle.Builder back = NewOrderSingle.newBuilder();
        codec.withLimits(codec.maxMessageSize(), 16).unmarshal(data, back);
        assertEquals(16, back.getFillsCount());
        assertEquals(64 << 20, codec.maxMessageSize());
        assertEquals(codec.maxMessageSize(), codec.maxRepeatedCount());
    }

    @Test
    void maxMessageSizeIsCheckedBeforeTheHeader() {
        Codec codec = Codec.of(NewOrderSingle.getDescriptor().getFile());
        byte[] data = codec.marshal(withFills(16));
        reject(codec.withLimits(data.length - 1, codec.maxRepeatedCount()), data, "exceeds MaxMessageSize=" + (data.length - 1));
        codec.withLimits(data.length, codec.maxRepeatedCount()).unmarshal(data, NewOrderSingle.newBuilder());
        byte[] huge = new byte[(64 << 20) + 1];
        reject(codec, huge, "MaxMessageSize=" + (64 << 20));
    }

    // The corpus's three malformed-group rows: a root block shorter than the
    // template's, a group header whose count × blockLength overflows int and
    // outruns the data, and a zero entry block with a non-zero count.
    @Test
    void malformedBlocksAndGroupHeadersAreRejected() {
        Codec codec = Codec.of(NewOrderSingle.getDescriptor().getFile());
        byte[] good = codec.marshal(withFills(2));
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(good).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int rootBlock = Short.toUnsignedInt(buf.getShort(0));
        int groupPos = 8 + rootBlock;

        byte[] shortBlock = good.clone();
        java.nio.ByteBuffer.wrap(shortBlock).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(0, (short) (rootBlock - 2));
        reject(codec, shortBlock, "wire blockLength " + (rootBlock - 2) + " < schema blockLength " + rootBlock);

        byte[] overflow = good.clone();
        java.nio.ByteBuffer ob = java.nio.ByteBuffer.wrap(overflow).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        ob.putShort(groupPos, (short) 0xFFFF);
        ob.putShort(groupPos + 2, (short) 0xFFFF);
        reject(codec, overflow, "declares 65535 entries × 65535 bytes");

        byte[] zeroBlock = good.clone();
        java.nio.ByteBuffer zb = java.nio.ByteBuffer.wrap(zeroBlock).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        zb.putShort(groupPos, (short) 0);
        zb.putShort(groupPos + 2, (short) 10000);
        reject(codec, zeroBlock, "wire blockLength 0 < schema blockLength");
    }
}
