// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.AllTypes;
import org.protowire.pxf.testproto.BigNums;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The values the spec's write gate found this tier writing differently from
 * protowire-go (protowire#511). Each expected value is the bytes Go writes for
 * the same field, or Go's error; the lite tier's WriteGateLiteTest pins the
 * same on its side.
 */
final class WriteGateValuesTest {

    private static String bigNums(String text) {
        BigNums.Builder b = BigNums.newBuilder();
        Pxf.unmarshal(text.getBytes(StandardCharsets.UTF_8), b);
        return HexFormat.of().formatHex(b.build().toByteArray());
    }

    private static String allTypes(String text) {
        AllTypes.Builder b = AllTypes.newBuilder();
        Pxf.unmarshal(text.getBytes(StandardCharsets.UTF_8), b);
        return HexFormat.of().formatHex(b.build().toByteArray());
    }

    // #157: a BigDecimal has no negative zero, so the sign was lost on the way.
    @Test
    void decimalNegativeZeroKeepsItsSign() {
        assertEquals("12021801", bigNums("decimal = -0"));
        assertEquals("120410021801", bigNums("decimal = -0.00"));
        assertEquals("12021002", bigNums("decimal = 0.00"), "a positive zero sets no sign");
    }

    @Test
    void decimalNegativeZeroPrintsWithItsSign() {
        BigNums.Builder b = BigNums.newBuilder();
        Pxf.unmarshal("decimal = -0.00".getBytes(StandardCharsets.UTF_8), b);
        String text = new String(Pxf.marshal(b.build()), StandardCharsets.UTF_8);
        assertTrue(text.contains("decimal = -0.00\n"), text);
    }

    // #158: draft -01 § Numeric Literals, a literal that rounds to infinity is refused.
    @Test
    void largestFiniteDoubleIsAcceptedAndTheNextIsRefused() {
        assertEquals("39ffffffffffffef7f", allTypes("double_field = 1.7976931348623157e308"));
        PxfException e = assertThrows(PxfException.class, () -> allTypes("double_field = 1e400"));
        assertTrue(e.getMessage().contains("1:16") && e.getMessage().contains("invalid double: 1e400"), e.getMessage());
        // The first literal past the halfway point to 2^1024 rounds to infinity.
        assertThrows(PxfException.class, () -> allTypes("double_field = 1.7976931348623159e308"));
        assertThrows(PxfException.class, () -> allTypes("double_field = -1e400"));
    }

    @Test
    void largestFiniteFloatIsAcceptedAndTheNextIsRefused() {
        assertEquals("35ffff7f7f", allTypes("float_field = 3.4028235e38"));
        PxfException e = assertThrows(PxfException.class, () -> allTypes("float_field = 3.4028236e38"));
        assertTrue(e.getMessage().contains("invalid float: 3.4028236e38"), e.getMessage());
    }

    @Test
    void infinityIsStillWrittenByName() {
        assertEquals("39000000000000f07f", allTypes("double_field = inf"));
        assertEquals("39000000000000f0ff", allTypes("double_field = -inf"));
    }
}
