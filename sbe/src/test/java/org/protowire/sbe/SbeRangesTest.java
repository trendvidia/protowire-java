// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Values an {@code (sbe.encoding)} primitive or a field cannot hold are
 * refused, not cut to their low bits (#197, protowire-go#252); a schema the
 * 16-bit header cannot describe does not bind, and a group past numInGroup
 * is not written (#198, protowire-go#250). Each expected text and byte
 * string is protowire-go v1.17.0's, run on the same .proto files
 * ({@code sbe/src/test/proto/ranges/}).
 */
final class SbeRangesTest {

    private static final Descriptor R = ranges.v1.ROuterClass.R.getDescriptor();
    private static final Codec CODEC = Codec.of(ranges.v1.ROuterClass.getDescriptor());

    private static DynamicMessage r(String field, Object value) {
        FieldDescriptor fd = R.findFieldByName(field);
        Object v = fd.getType() == FieldDescriptor.Type.ENUM
                ? fd.getEnumType().findValueByNumberCreatingIfUnknown((Integer) value)
                : value;
        return DynamicMessage.newBuilder(R).setField(fd, v).build();
    }

    private static String hex(byte[] b) { return HexFormat.of().formatHex(b); }

    @Test
    void aValueThePrimitiveCannotHoldIsNotWritten() {
        Map<String, Object[]> refused = Map.of(
            "sbe: field ranges.v1.R.i8: value 300 is outside the range of int8, [-128, 127]", new Object[] {"i8", 300},
            "sbe: field ranges.v1.R.u16: value -1 is outside the range of uint16, [0, 65535]", new Object[] {"u16", -1},
            "sbe: field ranges.v1.R.color: value 300 is outside the range of uint8, [0, 255]", new Object[] {"color", 300},
            "sbe: field ranges.v1.R.color: value -1 is outside the range of uint8, [0, 255]", new Object[] {"color", -1},
            "sbe: field ranges.v1.R.f: value 1e+39 is outside the range of float, ±3.4028234663852886e+38", new Object[] {"f", 1e39},
            "sbe: field ranges.v1.R.u_i8: value 200 is outside the range of int8, [-128, 127]", new Object[] {"u_i8", 200});
        refused.forEach((want, c) -> assertEquals(want,
            assertThrows(IllegalArgumentException.class, () -> CODEC.marshal(r((String) c[0], c[1])), want).getMessage()));
    }

    /** What fits is written by value, mismatched signedness included; bool with int8 used to throw ClassCastException. */
    @Test
    void aValueThatFitsIsWrittenByValue() {
        assertEquals("160001000a00000080000000000000000000000000000000000000000000", hex(CODEC.marshal(r("i8", -128))));
        assertEquals("160001000a000000000000000000807f0000000000000000000000000000",
            hex(CODEC.marshal(r("f", Double.POSITIVE_INFINITY))));
        assertEquals("160001000a00000000000000000000000000000000000000000000000100", hex(CODEC.marshal(r("b8", true))));
        assertEquals("160001000a00000000000000000000000000000000000000000000000005", hex(CODEC.marshal(r("u_i8", 5))));
    }

    @Test
    void aWireValueTheFieldCannotHoldIsNotRead() {
        assertEquals("sbe: field ranges.v1.R.from_u32: wire value 2147483648 (uint32) does not fit a int32 field, [-2147483648, 2147483647]",
            assertThrows(IllegalArgumentException.class, () -> CODEC.unmarshalDescriptor(
                HexFormat.of().parseHex("160001000a00000000000000000000000000008000000000000000000000"), R)).getMessage());
        assertEquals("sbe: field ranges.v1.R.from_d: wire value 1e+39 does not fit a float field, ±3.4028234663852886e+38",
            assertThrows(IllegalArgumentException.class, () -> CODEC.unmarshalDescriptor(
                HexFormat.of().parseHex("160001000a0000000000000000000000000000001d4a9cf4878207480000"), R)).getMessage());
        assertEquals("sbe: field ranges.v1.R.u_i8: wire value -1 (int8) does not fit a uint32 field, [0, 4294967295]",
            assertThrows(IllegalArgumentException.class, () -> CODEC.unmarshalDescriptor(
                HexFormat.of().parseHex("160001000a000000000000000000000000000000000000000000000000ff"), R)).getMessage());
    }

    /** uint32 with int8 used to throw IllegalStateException even for 5. */
    @Test
    void aWireValueThatFitsIsReadByValue() {
        DynamicMessage m = CODEC.unmarshalDescriptor(
            HexFormat.of().parseHex("160001000a00000000000000000000000000000000000000000000000005"), R);
        assertEquals(5, m.getField(R.findFieldByName("u_i8")));
        m = CODEC.unmarshalDescriptor(HexFormat.of().parseHex("160001000a0000000000000000000000000000000000000000000000ff00"), R);
        assertEquals(true, m.getField(R.findFieldByName("b8")));
        m = CODEC.unmarshalDescriptor(HexFormat.of().parseHex("160001000a000000ff000000000000000000000000000000000000000000"), R);
        assertEquals(-1, m.getField(R.findFieldByName("i8")));
    }

    @Test
    void aGroupPastNumInGroupIsNotWritten() {
        Descriptor g = ranges.v1.ROuterClass.G.getDescriptor();
        Descriptor e = ranges.v1.ROuterClass.E.getDescriptor();
        FieldDescriptor es = g.findFieldByName("es");
        DynamicMessage entry = DynamicMessage.newBuilder(e).build();
        DynamicMessage.Builder b = DynamicMessage.newBuilder(g);
        for (int i = 0; i < 65535; i++) b.addRepeatedField(es, entry);
        assertEquals(262152, CODEC.marshal(b.build()).length);
        b.addRepeatedField(es, entry);
        DynamicMessage past = b.build();
        assertEquals("sbe: field ranges.v1.G.es has 65536 entries, past 65535, the largest numInGroup SBE can state",
            assertThrows(IllegalArgumentException.class, () -> CODEC.marshal(past)).getMessage());
    }

    @Test
    void bindingRefusesWhatGoRefuses() {
        Map<FileDescriptor, String> refused = Map.ofEntries(
            Map.entry(ranges.stringenc.v1.StringEnc.getDescriptor(), "sbe: field ranges.stringenc.v1.S.s: (sbe.encoding) = \"int32\""
                + " on a string field; it narrows a numeric field, and a string field takes (sbe.length)"),
            Map.entry(ranges.intfloat.v1.IntFloat.getDescriptor(),
                "sbe: field ranges.intfloat.v1.S.n: (sbe.encoding) = \"float\" is a floating-point primitive and the field is int32"),
            Map.entry(ranges.doubleint.v1.DoubleInt.getDescriptor(),
                "sbe: field ranges.doubleint.v1.S.d: (sbe.encoding) = \"int32\" is an integer primitive and the field is double"),
            Map.entry(ranges.length.v1.Length.getDescriptor(),
                "sbe: field ranges.length.v1.S.s: (sbe.length) = 65537 is past 65535, the largest blockLength SBE can state"),
            Map.entry(ranges.block.v1.Block.getDescriptor(),
                "sbe: field ranges.block.v1.S.b: ends at byte 80000 of its block, past 65535, the largest blockLength SBE can state"),
            Map.entry(ranges.tid.v1.Tid.getDescriptor(),
                "sbe: message ranges.tid.v1.S: (sbe.template_id) = 65537 is past 65535, the largest templateId SBE can state"),
            Map.entry(ranges.schema.v1.Schema.getDescriptor(),
                "sbe: file ranges/schema.proto: (sbe.schema_id) = 65537 is past 65535, the largest schemaId SBE can state"),
            Map.entry(ranges.version.v1.Version.getDescriptor(),
                "sbe: file ranges/version.proto: (sbe.version) = 65538 is past 65535, the largest version SBE can state"),
            Map.entry(ranges.oneofcomposite.v1.OneofComposite.getDescriptor(),
                "sbe: composite ranges.oneofcomposite.v1.S.c: composite ranges.oneofcomposite.v1.C contains oneof field a"));
        refused.forEach((fd, want) -> assertEquals(want,
            assertThrows(IllegalArgumentException.class, () -> Codec.of(fd), fd.getName()).getMessage(), fd.getName()));
        // 65535 is the largest each can state, and binds.
        Codec.of(ranges.lengthok.v1.LengthOk.getDescriptor());
        Codec.of(ranges.tidok.v1.TidOk.getDescriptor());
    }
}
