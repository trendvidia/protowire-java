package org.protowire.lite.it;

import com.google.protobuf.BoolValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Duration;
import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.protowire.proto.pxf.BigFloat;
import org.protowire.proto.pxf.BigInt;
import org.protowire.proto.pxf.Decimal;
import wkt.test.v1.AllWkt;
import wkt.test.v1.AllWktPxfCodec;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end smoke covering the full lite codegen chain for a WKT-bearing
 * schema. Drives the round-trip exclusively through the codegen-emitted
 * {@link AllWktPxfCodec#unmarshal(String)} and {@link AllWktPxfCodec#marshal}
 * entry points — no hand-built {@code PxfMeta}, no direct reach into
 * {@code LiteWireWriter} / {@code LiteWireReader}.
 *
 * <p>What this validates that the unit tests in {@code :pxf-android} can't:
 * <ul>
 *   <li>{@code protoc-gen-pxf-java-meta}'s {@code WELL_KNOWN_KINDS} emit
 *       resolves correctly through the {@code @Override} accessor on the
 *       generated {@code AllWktPxfMeta}.
 *   <li>The encoder migration to {@code wellKnownKinds()} works against
 *       a real codegen-emitted meta (not a hand-built test fixture).
 *   <li>Decoder + encoder agree on every WKT — Timestamp / Duration / 4
 *       wrappers / 3 bignum types — when driven by the typed
 *       {@code <Message>PxfCodec.{marshal,unmarshal}} pair.
 * </ul>
 */
final class AllWktRoundTripTest {

    /**
     * Canonical PXF text exercising every WKT field in AllWkt. Each value is
     * non-default so default-omission doesn't mask coverage gaps.
     */
    private static final String CANONICAL = String.join("\n",
        "ts = 2024-01-15T10:30:45Z",
        "dur = 1h30m",
        "str_w = \"alice\"",
        "i32_w = 42",
        "bool_w = true",
        "dbl_w = 3.14",
        "big_int = 12345678901234567890123456789",
        "dec = 19.95",
        "big_float = 0.0001234567"
    );

    @Test
    void unmarshal_pxfText_matchesTypedBuilder() {
        AllWkt fromPxf = AllWktPxfCodec.unmarshal(CANONICAL);
        AllWkt fromTyped = canonicalTyped();
        assertArrayEquals(fromTyped.toByteArray(), fromPxf.toByteArray(),
            "wire bytes from PXF unmarshal disagree with typed-builder construction");
    }

    @Test
    void marshal_typed_roundTripsThroughPxfText() {
        AllWkt original = canonicalTyped();
        String pxfText = AllWktPxfCodec.marshal(original);
        AllWkt reparsed = AllWktPxfCodec.unmarshal(pxfText);
        assertArrayEquals(original.toByteArray(), reparsed.toByteArray(),
            "marshal → unmarshal round-trip lost or reordered information.\n" +
            "Marshal output:\n" + pxfText);
    }

    @Test
    void marshal_unmarshal_reEncode_byteEquivalent() {
        // Stronger version of the round-trip: take the canonical PXF text,
        // unmarshal → typed, marshal back to text, unmarshal again → typed,
        // and assert the second-pass wire bytes match the first-pass.
        AllWkt firstPass = AllWktPxfCodec.unmarshal(CANONICAL);
        String roundTrippedText = AllWktPxfCodec.marshal(firstPass);
        AllWkt secondPass = AllWktPxfCodec.unmarshal(roundTrippedText);
        assertArrayEquals(firstPass.toByteArray(), secondPass.toByteArray(),
            "encode → marshal-back → re-encode wire bytes diverge.\n" +
            "Round-trip text:\n" + roundTrippedText);
    }

    @Test
    void marshal_emitsAllWktKinds() {
        // Sanity check that the marshal output preserves every WKT in some
        // recognizable form. Doesn't pin exact text (the formatter's whitespace
        // and ordering aren't part of the contract) — just confirms the values
        // round-trip-encode to non-empty bytes for each field.
        String text = AllWktPxfCodec.marshal(canonicalTyped());
        for (String fragment : new String[]{
                "ts = ", "dur = ", "str_w = ", "i32_w = ",
                "bool_w = ", "dbl_w = ", "big_int = ", "dec = ", "big_float = "}) {
            assertEquals(true, text.contains(fragment),
                "marshal output missing field '" + fragment + "':\n" + text);
        }
    }

    /**
     * Programmatic equivalent of {@link #CANONICAL} — the typed-builder path
     * that {@code unmarshal} should produce identical bytes to.
     */
    private static AllWkt canonicalTyped() {
        return AllWkt.newBuilder()
            .setTs(Timestamp.newBuilder().setSeconds(1705314645).setNanos(0).build())
            .setDur(Duration.newBuilder().setSeconds(5400).setNanos(0).build())
            .setStrW(StringValue.of("alice"))
            .setI32W(Int32Value.of(42))
            .setBoolW(BoolValue.of(true))
            .setDblW(DoubleValue.of(3.14))
            .setBigInt(BigInt.newBuilder()
                .setAbs(unsignedBytes(new java.math.BigInteger("12345678901234567890123456789")))
                .setNegative(false)
                .build())
            .setDec(Decimal.newBuilder()
                .setUnscaled(unsignedBytes(java.math.BigInteger.valueOf(1995)))
                .setScale(2)
                .setNegative(false)
                .build())
            .setBigFloat(BigFloat.newBuilder()
                .setMantissa(unsignedBytes(java.math.BigInteger.valueOf(1234567)))
                .setExponent(-10)
                .setPrec(java.math.BigInteger.valueOf(1234567).bitLength())
                .setNegative(false)
                .build())
            .build();
    }

    /** Sign-trimmed unsigned big-endian magnitude bytes — matches LiteWireWriter's encoding. */
    private static ByteString unsignedBytes(java.math.BigInteger value) {
        if (value.signum() == 0) return ByteString.EMPTY;
        byte[] raw = value.abs().toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            raw = Arrays.copyOfRange(raw, 1, raw.length);
        }
        return ByteString.copyFrom(raw);
    }
}
