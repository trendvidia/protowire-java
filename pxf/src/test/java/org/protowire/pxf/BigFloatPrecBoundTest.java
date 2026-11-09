// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.BigNums;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The full tier's half of #201: a BigFloat precision past an int is a
 * positioned PxfException with protowire-go's text, not an
 * ArithmeticException, and a zero whose precision is past the digit bound is
 * not written. {@code BigFloatsPrecBoundTest} holds the shared code to each
 * of Go's cases; these hold the decoder and the writer to it.
 */
class BigFloatPrecBoundTest {

    /** Wide enough for a p4294967295 suffix: its literal has 1292913988 digits. */
    private static final UnmarshalOptions RAISED = UnmarshalOptions.defaults()
            .withLimits(DecodeLimits.defaults().withMaxNumericLiteralDigits(1_300_000_000));

    @Test
    void aSuffixPastAnIntIsAPositionedPxfException() {
        PxfException e = assertThrows(PxfException.class, () -> RAISED.unmarshal(
                "big_float = 1p2147483648".getBytes(StandardCharsets.UTF_8), BigNums.newBuilder()));
        assertEquals("1:13: big float literal 1: precision p2147483648 exceeds the widest mantissa big.Float can hold,"
                + " 2147483647 bits", e.getMessage());
    }

    @Test
    void aValueBelowTheWiresExponentRangeIsAPositionedPxfException() {
        PxfException e = assertThrows(PxfException.class, () -> RAISED.unmarshal(
                "big_float = 1e-300p2147483647".getBytes(StandardCharsets.UTF_8), BigNums.newBuilder()));
        assertEquals("1:13: big float literal 1e-300 is below the wire's exponent range", e.getMessage());
    }

    /** {@code big_float = {prec: 13604}}: a zero whose literal, 0p13604, every reader refuses. */
    @Test
    void aZeroPastTheDigitBoundIsNotWritten() throws Exception {
        BigNums m = BigNums.parseFrom(HexFormat.of().parseHex("1a0318a46a"));
        PxfException e = assertThrows(PxfException.class, () -> Pxf.marshal(m));
        assertEquals(e.position() + ": pxf.BigFloat prec 13604 bits renders to 4097 digits; MaxNumericLiteralDigits=4096",
                e.getMessage());
        // At the bound it is written, and reads back.
        BigNums at = BigNums.parseFrom(HexFormat.of().parseHex("1a0318a36a"));
        String text = new String(Pxf.marshal(at), StandardCharsets.UTF_8);
        assertEquals("big_float = 0p13603\n", text);
        BigNums.Builder back = BigNums.newBuilder();
        Pxf.unmarshal(text.getBytes(StandardCharsets.UTF_8), back);
        assertEquals(13603, back.getBigFloat().getPrec());
    }
}
