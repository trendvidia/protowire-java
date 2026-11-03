// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.it;

import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.protowire.pxf.DecodeLimits;
import org.protowire.pxf.Parser;
import org.protowire.pxf.PxfException;
import org.protowire.pxf.PxfMeta;
import org.protowire.pxf.android.LiteWireWriter;
import text.v1.ScalarsPxfMeta;
import text.v1.ShapesPxfMeta;
import text.v1.WellKnown;
import text.v1.WellKnownPxfCodec;
import text.v1.WellKnownPxfMeta;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The documents on which the spec's write gate found LiteWireWriter writing
 * different bytes from protowire-go (protowire#511; #156, #157, #158). Each
 * expected value is the golden Go wrote for the same document
 * (protowire testdata/write/golden/), or Go's error. The full tier's
 * WriteGateValuesTest pins the same values on its side.
 */
final class WriteGateLiteTest {

    private static String encode(String pxf, PxfMeta meta) {
        return HexFormat.of().formatHex(LiteWireWriter.encode(
            Parser.parse(pxf.getBytes(StandardCharsets.UTF_8), DecodeLimits.defaults()), meta, DecodeLimits.defaults()));
    }

    // #156: a singular field with no presence that holds its zero value writes nothing.
    @ParameterizedTest
    @ValueSource(strings = {
        "f_bool = false",
        "f_bytes = b\"\"",
        "f_double = 0.0",
        "f_enum = COLOR_UNSPECIFIED",
        "f_float = 1e-46",
        "f_int32 = 0",
        "f_sint64 = -0",
        "f_string = \"\"",
    })
    void zeroWithoutPresenceWritesNothing(String pxf) {
        assertEquals("", encode(pxf, ScalarsPxfMeta.INSTANCE));
    }

    @Test
    void negativeZeroIsNotTheZeroValue() {
        // -0.0 has a bit set, and protobuf writes it.
        assertEquals("0900000000000000801500000080", encode("f_double = -0\nf_float = -0\n", ScalarsPxfMeta.INSTANCE));
    }

    @Test
    void zeroWithPresenceIsWrittenInFieldOrder() {
        // Both are proto3 optional, so the zeros stay; opt_string (2) goes before opt_int (3).
        assertEquals("12001800", encode("opt_int = 0\nopt_string = \"\"\n", ShapesPxfMeta.INSTANCE));
    }

    @Test
    void aMapEntryKeepsAZeroKeyAndValue() {
        assertEquals("520408001200", encode("by_int = {\n  0: \"\"\n}\n", ShapesPxfMeta.INSTANCE));
    }

    @Test
    void fieldsGoInFieldNumberOrderAndMapsInKeyOrder() {
        // stamps (18) is bound before decs (17), and each map lists its keys backwards.
        assertEquals(
            "8a01070a0161120208028a01070a0162120208019201080a016112030a010292010a0a017a12050a010f1001",
            encode("stamps = {\n  b: 1970-01-01T00:00:01Z\n  a: 1970-01-01T00:00:02Z\n}\ndecs = {\n  z: 1.5\n  a: 2\n}\n",
                WellKnownPxfMeta.INSTANCE));
    }

    // #156: a Decimal literal takes no exponent.
    @ParameterizedTest
    @ValueSource(strings = {"1e3", "1E-2"})
    void decimalExponentIsRefused(String literal) {
        PxfException e = assertThrows(PxfException.class, () -> encode("dec = " + literal, WellKnownPxfMeta.INSTANCE));
        assertTrue(e.getMessage().contains("1:7") && e.getMessage().contains("invalid decimal: " + literal), e.getMessage());
    }

    // #157: the sign of a zero is kept, both ways.
    @ParameterizedTest
    @CsvSource({"-0, 6a021801", "-0.00, 6a0410021801", "1.00, 6a050a01641002"})
    void decimalKeepsItsSignAndScale(String literal, String golden) throws InvalidProtocolBufferException {
        assertEquals(golden, encode("dec = " + literal, WellKnownPxfMeta.INSTANCE));
        assertEquals("dec = " + literal + "\n",
            WellKnownPxfCodec.marshal(WellKnown.parseFrom(HexFormat.of().parseHex(golden))));
    }

    // #158: a finite literal that rounds to infinity is refused.
    @Test
    void doubleOverflowIsRefusedAtTheBoundary() {
        assertEquals("09ffffffffffffef7f", encode("f_double = 1.7976931348623157e308", ScalarsPxfMeta.INSTANCE));
        PxfException e = assertThrows(PxfException.class, () -> encode("f_double = 1e400", ScalarsPxfMeta.INSTANCE));
        assertTrue(e.getMessage().contains("1:12") && e.getMessage().contains("invalid double: 1e400"), e.getMessage());
    }

    @Test
    void floatOverflowIsRefusedAtTheBoundary() {
        assertEquals("15ffff7f7f", encode("f_float = 3.4028235e38", ScalarsPxfMeta.INSTANCE));
        PxfException e = assertThrows(PxfException.class, () -> encode("f_float = 3.4028236e38", ScalarsPxfMeta.INSTANCE));
        assertTrue(e.getMessage().contains("1:11") && e.getMessage().contains("invalid float: 3.4028236e38"), e.getMessage());
    }
}
