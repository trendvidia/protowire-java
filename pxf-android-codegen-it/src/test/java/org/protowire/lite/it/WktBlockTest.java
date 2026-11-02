// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.it;

import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import org.junit.jupiter.api.Test;
import org.protowire.proto.pxf.BigFloat;
import org.protowire.proto.pxf.BigInt;
import org.protowire.proto.pxf.Decimal;
import org.protowire.pxf.PxfException;
import wkt.test.v1.Blocks;
import wkt.test.v1.BlocksPxfCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Draft -01 § Well-Known Type Shapes: a value takes its literal or a block
 * naming its fields, in a singular field, a list element or a map value. Each
 * runs through the generated companion with no registry (#140).
 */
final class WktBlockTest {
    private static final ByteString FIVE = ByteString.copyFrom(new byte[] {5});

    private static void assertBinds(String doc, Blocks want) {
        assertEquals(want, BlocksPxfCodec.unmarshal(doc), doc);
    }

    /** Both spellings of a block bind the same value. */
    private static void assertBlock(String field, String body, Blocks want) {
        assertBinds(field + " { " + body + " }", want);
        assertBinds(field + " = { " + body + " }", want);
    }

    @Test
    void wrappers() {
        assertBlock("str_w", "value = \"x\"", Blocks.newBuilder().setStrW(StringValue.of("x")).build());
        assertBlock("bytes_w", "value = b\"BQ==\"", Blocks.newBuilder().setBytesW(BytesValue.of(FIVE)).build());
        assertBlock("bool_w", "value = true", Blocks.newBuilder().setBoolW(BoolValue.of(true)).build());
        assertBlock("i32_w", "value = -5", Blocks.newBuilder().setI32W(Int32Value.of(-5)).build());
        assertBlock("i64_w", "value = -5000000000", Blocks.newBuilder().setI64W(Int64Value.of(-5_000_000_000L)).build());
        assertBlock("u32_w", "value = 4294967295", Blocks.newBuilder().setU32W(UInt32Value.of(-1)).build());
        assertBlock("u64_w", "value = 18446744073709551615", Blocks.newBuilder().setU64W(UInt64Value.of(-1L)).build());
        assertBlock("flt_w", "value = 1.5", Blocks.newBuilder().setFltW(FloatValue.of(1.5f)).build());
        assertBlock("dbl_w", "value = 2.5", Blocks.newBuilder().setDblW(DoubleValue.of(2.5)).build());
    }

    @Test
    void emptyBlockSetsTheField() {
        assertTrue(BlocksPxfCodec.unmarshal("i32_w { }").hasI32W());
        assertTrue(BlocksPxfCodec.unmarshal("big_int { }").hasBigInt());
    }

    @Test
    void bigInt() {
        assertBlock("big_int", "abs = b\"BQ==\" negative = true",
            Blocks.newBuilder().setBigInt(BigInt.newBuilder().setAbs(FIVE).setNegative(true)).build());
        assertBlock("big_int", "negative = true",
            Blocks.newBuilder().setBigInt(BigInt.newBuilder().setNegative(true)).build());
    }

    @Test
    void decimal() {
        assertBlock("dec", "unscaled = b\"BQ==\" scale = 2 negative = true",
            Blocks.newBuilder().setDec(Decimal.newBuilder().setUnscaled(FIVE).setScale(2).setNegative(true)).build());
    }

    @Test
    void bigFloat() {
        assertBlock("big_float", "mantissa = b\"BQ==\" exponent = -3 prec = 64 negative = true",
            Blocks.newBuilder().setBigFloat(BigFloat.newBuilder()
                .setMantissa(FIVE).setExponent(-3).setPrec(64).setNegative(true)).build());
    }

    @Test
    void listElementsTakeEitherForm() {
        assertBinds("ints = [ 5, { abs = b\"BQ==\" negative = true } ]", Blocks.newBuilder()
            .addInts(BigInt.newBuilder().setAbs(FIVE))
            .addInts(BigInt.newBuilder().setAbs(FIVE).setNegative(true))
            .build());
        assertBinds("wrapped = [ 1, { value = 2 } ]", Blocks.newBuilder()
            .addWrapped(Int32Value.of(1)).addWrapped(Int32Value.of(2)).build());
    }

    @Test
    void mapValuesTakeEitherForm() {
        assertBinds("decs = { a: 0.05 b: { unscaled = b\"BQ==\" scale = 2 } }", Blocks.newBuilder()
            .putDecs("a", Decimal.newBuilder().setUnscaled(FIVE).setScale(2).build())
            .putDecs("b", Decimal.newBuilder().setUnscaled(FIVE).setScale(2).build())
            .build());
        assertBinds("names = { a: \"x\" b: { value = \"y\" } }", Blocks.newBuilder()
            .putNames("a", StringValue.of("x")).putNames("b", StringValue.of("y")).build());
    }

    @Test
    void blockFieldsAreCheckedAgainstTheShape() {
        assertThrows(IllegalArgumentException.class, () -> BlocksPxfCodec.unmarshal("i32_w { nope = 1 }"));
        assertThrows(PxfException.class, () -> BlocksPxfCodec.unmarshal("i32_w { value = 5000000000 }"));
        assertThrows(PxfException.class, () -> BlocksPxfCodec.unmarshal("big_int { negative = null }"));
    }

    @Test
    void aLiteralStillBinds() {
        assertBinds("big_int = 5", Blocks.newBuilder().setBigInt(BigInt.newBuilder().setAbs(FIVE)).build());
        assertThrows(IllegalArgumentException.class, () -> BlocksPxfCodec.unmarshal("big_int = \"x\""));
    }
}
