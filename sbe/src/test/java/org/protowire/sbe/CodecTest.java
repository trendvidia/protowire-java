package org.protowire.sbe;

import org.protowire.sbe.runtime.View;
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
}
