package org.protowire.sbe.it;

import org.junit.jupiter.api.Test;
import org.protowire.sbe.testproto.NewOrderSingle;
import org.protowire.sbe.testproto.NewOrderSingleSbeCodec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end smoke for the lite-tier SBE codegen path. Drives the round-
 * trip exclusively through the codegen-emitted
 * {@link NewOrderSingleSbeCodec#marshal(NewOrderSingle)} /
 * {@link NewOrderSingleSbeCodec#unmarshal(byte[])} pair — no hand-built
 * {@code MessageTemplate}, no descriptor reflection.
 *
 * <p>Validates:
 * <ul>
 *   <li>{@code protoc-gen-pxf-java-meta}'s SBE emit produces compilable
 *       Java against {@code protobuf-javalite} typed message classes.</li>
 *   <li>Layout (29-byte block + 20-byte group entries) matches what the
 *       descriptor-driven {@code :sbe} {@code TemplateBuilder} produces.</li>
 *   <li>Wire bytes from {@link NewOrderSingleSbeCodec#marshal} round-trip
 *       through {@link NewOrderSingleSbeCodec#unmarshal} byte-equivalent.</li>
 *   <li>Group field handling (the 3-entry {@code fills} repeated group)
 *       drives the borrow / commit pattern in the emitted writer.</li>
 * </ul>
 */
final class NewOrderSingleSbeRoundTripTest {

    @Test
    void roundTrip_scalarsGroupAndComposite() {
        NewOrderSingle original = NewOrderSingle.newBuilder()
            .setOrderId(123456789L)
            .setSymbol("AAPL")
            .setPrice(15050L) // $150.50 in cents
            .setQuantity(100)
            .setSide(1) // (sbe.encoding) = "uint8" → fits in 1 byte
            .addFills(NewOrderSingle.Fill.newBuilder()
                .setFillPrice(15049L)
                .setFillQty(50)
                .setFillId(1L)
                .build())
            .addFills(NewOrderSingle.Fill.newBuilder()
                .setFillPrice(15051L)
                .setFillQty(30)
                .setFillId(2L)
                .build())
            .addFills(NewOrderSingle.Fill.newBuilder()
                .setFillPrice(15050L)
                .setFillQty(20)
                .setFillId(3L)
                .build())
            // Composite: lays out flat into the root block (8 + 4 = 12 bytes).
            .setLimitPrice(NewOrderSingle.Decimal.newBuilder()
                .setMantissa(155000L)
                .setScale(2)
                .build())
            .build();

        byte[] wire = NewOrderSingleSbeCodec.marshal(original);
        // Header (8) + root block (41 = 29 scalars + 12 composite) + group
        // header (4) + 3 entries × 20.
        assertEquals(8 + 41 + 4 + 3 * 20, wire.length,
            "wire size disagrees with codegen-emitted layout");

        NewOrderSingle decoded = NewOrderSingleSbeCodec.unmarshal(wire);
        assertEquals(original.getOrderId(), decoded.getOrderId());
        assertEquals(original.getSymbol(), decoded.getSymbol());
        assertEquals(original.getPrice(), decoded.getPrice());
        assertEquals(original.getQuantity(), decoded.getQuantity());
        assertEquals(original.getSide(), decoded.getSide());
        assertEquals(original.getLimitPrice().getMantissa(), decoded.getLimitPrice().getMantissa());
        assertEquals(original.getLimitPrice().getScale(),    decoded.getLimitPrice().getScale());
        assertEquals(original.getFillsCount(), decoded.getFillsCount());
        for (int i = 0; i < original.getFillsCount(); i++) {
            assertEquals(original.getFills(i).getFillPrice(), decoded.getFills(i).getFillPrice());
            assertEquals(original.getFills(i).getFillQty(),   decoded.getFills(i).getFillQty());
            assertEquals(original.getFills(i).getFillId(),    decoded.getFills(i).getFillId());
        }

        // Re-marshal the decoded message; second-pass wire bytes must match
        // the first-pass exactly. Catches any encode/decode asymmetry.
        byte[] reWire = NewOrderSingleSbeCodec.marshal(decoded);
        assertArrayEquals(wire, reWire,
            "second-pass wire bytes diverge from first-pass — codec is not stable");
    }

    @Test
    void roundTrip_emptyGroupAndDefaultComposite() {
        // No fills, no limit_price set — composite lays out as 12 zero
        // bytes. SBE has no presence-tracking inside the root block; an
        // unset composite encodes as default-zero.
        NewOrderSingle original = NewOrderSingle.newBuilder()
            .setOrderId(42L)
            .setSymbol("ZZZZ")
            .setPrice(1L)
            .setQuantity(1)
            .setSide(0)
            .build();

        byte[] wire = NewOrderSingleSbeCodec.marshal(original);
        // 8 + 41 (scalars + zero composite) + 4 (group header for count=0).
        assertEquals(8 + 41 + 4, wire.length);

        NewOrderSingle decoded = NewOrderSingleSbeCodec.unmarshal(wire);
        assertEquals(0, decoded.getFillsCount());
        assertEquals(42L, decoded.getOrderId());
        assertEquals(0L, decoded.getLimitPrice().getMantissa());
        assertEquals(0, decoded.getLimitPrice().getScale());
    }
}
