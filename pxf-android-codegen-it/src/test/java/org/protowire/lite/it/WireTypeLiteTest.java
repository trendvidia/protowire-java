// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.protowire.pxf.DecodeLimits;
import org.protowire.pxf.Format;
import org.protowire.pxf.PxfMeta;
import org.protowire.pxf.android.LiteWireReader;

import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LiteWireReader skips a field whose wire type does not match its declared
 * kind, as an unknown field, the way protowire-go and the full tier do
 * (#160). Each row is a wire message with such a field, usually followed by
 * a well-typed one to show the rest of the message is still read in step,
 * and the text protowire-go's {@code check_decode --format pb-text} printed
 * for it against the spec's testdata/text/text.proto (protowire-go 7465f91).
 *
 * <p>The rows cover each wire type arriving on a field of another kind
 * (varint, fixed32, fixed64, length-delimited, and a group), in a message,
 * in a repeated field, in a map entry, and inside every well-known payload
 * the reader spells as a literal. A repeated scalar field takes both its
 * packed and its unpacked record, which is not a mismatch. A map entry
 * whose key or value is missing, or skipped, has that field's zero value.
 */
final class WireTypeLiteTest {

    private static final Map<String, PxfMeta> METAS = Map.of(
        "Scalars", text.v1.ScalarsPxfMeta.INSTANCE,
        "Shapes", text.v1.ShapesPxfMeta.INSTANCE,
        "WellKnown", text.v1.WellKnownPxfMeta.INSTANCE);

    private static String decode(String schema, String hex) {
        return Format.formatDocument(LiteWireReader.toAst(
            HexFormat.of().parseHex(hex), METAS.get(schema), DecodeLimits.defaults()));
    }

    @Test
    void int64ValueWithAFixed32FieldIsZero() {
        // The issue's case: Int64Value (i64_w, field 7) whose field 1 is
        // fixed32. It was read as a varint and failed with "invalid tag (zero)".
        assertEquals("i64_w = 0\n", decode("WellKnown", "3a050d01000000"));
    }

    // {schema, case, wire hex, protowire-go's text}
    static Object[][] goCases() {
        return new Object[][] {
            {"Scalars", "bool-as-fixed64", "4101000000000000002807", "f_int32 = 7\n"},
            {"Scalars", "bytes-as-fixed32", "65010000002807", "f_int32 = 7\n"},
            {"Scalars", "double-as-fixed32", "0d010000002807", "f_int32 = 7\n"},
            {"Scalars", "enum-as-len", "7201012807", "f_int32 = 7\n"},
            {"Scalars", "fixed32-as-varint", "38052807", "f_int32 = 7\n"},
            {"Scalars", "fixed64-as-varint", "30052807", "f_int32 = 7\n"},
            {"Scalars", "float-as-fixed64", "1101000000000000002807", "f_int32 = 7\n"},
            {"Scalars", "set-then-mistyped", "28072d01000000", "f_int32 = 7\n"},
            {"Scalars", "sfixed32-as-len", "7a01052807", "f_int32 = 7\n"},
            {"Scalars", "string-as-varint", "48052807", "f_int32 = 7\n"},
            {"Scalars", "varint-as-fixed32", "1d010000002807", "f_int32 = 7\n"},
            {"Scalars", "varint-as-fixed64", "1901000000000000002807", "f_int32 = 7\n"},
            {"Scalars", "varint-as-group", "2b2c2807", "f_int32 = 7\n"},
            {"Scalars", "varint-as-len", "1a02aabb2807", "f_int32 = 7\n"},
            {"Shapes", "boolmap-key-absent", "5a03120178", "by_bool = {\n  false: \"x\"\n}\n"},
            {"Shapes", "boolmap-key-as-fixed32", "5a080d01000000120178", "by_bool = {\n  false: \"x\"\n}\n"},
            {"Shapes", "colors-as-fixed64", "38013901000000000000003802", "colors = [\n  COLOR_RED,\n  COLOR_GREEN\n]\n"},
            {"Shapes", "colors-packed-and-unpacked", "3a01013802", "colors = [\n  COLOR_RED,\n  COLOR_GREEN\n]\n"},
            {"Shapes", "intmap-key-absent", "5203120178", "by_int = {\n  0: \"x\"\n}\n"},
            {"Shapes", "intmap-key-as-len", "52060a0161120178", "by_int = {\n  0: \"x\"\n}\n"},
            {"Shapes", "ints-as-fixed32", "200125010000002002", "ints = [\n  1,\n  2\n]\n"},
            {"Shapes", "ints-packed", "2202010220032004", "ints = [\n  1,\n  2,\n  3,\n  4\n]\n"},
            {"Shapes", "itemmap-value-absent", "62030a0161", "by_item = {\n  a: {\n  }\n}\n"},
            {"Shapes", "items-as-varint", "300532030a0178", "items = [\n  {\n    name = \"x\"\n  }\n]\n"},
            {"Shapes", "map-as-varint", "48054a060a0161120178", "by_string = {\n  a: \"x\"\n}\n"},
            {"Shapes", "map-key-as-varint", "4a050805120178", "by_string = {\n  \"\": \"x\"\n}\n"},
            {"Shapes", "map-value-as-fixed32", "4a080a01611501000000", "by_string = {\n  a: \"\"\n}\n"},
            {"Shapes", "message-as-varint", "08051a0109", ""},
            {"Shapes", "oneof-as-varint", "68051a0109", ""},
            {"Shapes", "opt-int-as-fixed32", "1d010000001a0109", ""},
            {"Shapes", "strings-as-varint", "28052a0178", "strings = [\n  \"x\"\n]\n"},
            {"Shapes", "strmap-value-absent", "4a030a0161", "by_string = {\n  a: \"\"\n}\n"},
            {"WellKnown", "bigfloat-exp-as-fixed64", "720c0a0105110100000000000000", "big_float = 0\n"},
            {"WellKnown", "bigfloat-prec-as-len", "72060a01051a0105", "big_float = 0\n"},
            {"WellKnown", "bigint-abs-as-varint", "62020805", "big_int = 0\n"},
            {"WellKnown", "bigint-neg-as-len", "62060a0105120101", "big_int = 5\n"},
            {"WellKnown", "bool-as-len", "2a030a0101", "bool_w = false\n"},
            {"WellKnown", "bytes-as-fixed64", "2209090100000000000000", "bytes_w = b\"\"\n"},
            {"WellKnown", "dbl-as-fixed32", "5a050d01000000", "dbl_w = 0\n"},
            {"WellKnown", "dec-neg-as-fixed32", "6a080a01051d01000000", "dec = 5\n"},
            {"WellKnown", "dec-scale-as-len", "6a060a0105120102", "dec = 5\n"},
            {"WellKnown", "dec-unscaled-as-varint", "6a020805", "dec = 0\n"},
            {"WellKnown", "decs-scale-as-len", "92010b0a016112060a0105120102", "decs = {\n  a: 5\n}\n"},
            {"WellKnown", "decs-value-absent", "9201030a0161", "decs = {\n  a: 0\n}\n"},
            {"WellKnown", "dur-seconds-as-len", "12030a0141", "dur = 0s\n"},
            {"WellKnown", "durs-as-varint", "8001058201020805", "durs = [\n  5s\n]\n"},
            {"WellKnown", "flt-as-fixed64", "5209090100000000000000", "flt_w = 0\n"},
            {"WellKnown", "flt-as-varint", "52020805", "flt_w = 0\n"},
            {"WellKnown", "i32-as-fixed64", "3209090100000000000000", "i32_w = 0\n"},
            {"WellKnown", "i64-as-fixed32", "3a050d01000000", "i64_w = 0\n"},
            {"WellKnown", "secret-hint-as-fixed32", "7a080a01611501000000", "secret = \"a\"\n"},
            {"WellKnown", "secret-value-as-varint", "7a020805", "secret = \"\"\n"},
            {"WellKnown", "stamps-ts-inner-mistyped", "8a010a0a016112050d01000000", "stamps = {\n  a: 1970-01-01T00:00:00Z\n}\n"},
            {"WellKnown", "stamps-value-absent", "8a01030a0161", "stamps = {\n  a: 1970-01-01T00:00:00Z\n}\n"},
            {"WellKnown", "stamps-value-as-fixed32", "8a01090a0161120408011001", "stamps = {\n  a: 1970-01-01T00:00:01.000000001Z\n}\n"},
            {"WellKnown", "stamps-value-as-varint", "8a01050a01611005", "stamps = {\n  a: 1970-01-01T00:00:00Z\n}\n"},
            {"WellKnown", "str-as-varint", "1a020805", "str_w = \"\"\n"},
            {"WellKnown", "ts-nanos-as-len", "0a050801120101", "ts = 1970-01-01T00:00:01Z\n"},
            {"WellKnown", "ts-seconds-as-fixed32", "0a050d01000000", "ts = 1970-01-01T00:00:00Z\n"},
            {"WellKnown", "u32-as-len", "42030a0105", "u32_w = 0\n"},
            {"WellKnown", "u64-as-fixed32", "4a050d01000000", "u64_w = 0\n"},
            {"WellKnown", "wkt-field-as-varint", "08051a020a00", "str_w = \"\"\n"},
        };
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("goCases")
    void readsAsProtowireGoDoes(String schema, String name, String hex, String goText) {
        assertEquals(goText, decode(schema, hex));
    }
}
