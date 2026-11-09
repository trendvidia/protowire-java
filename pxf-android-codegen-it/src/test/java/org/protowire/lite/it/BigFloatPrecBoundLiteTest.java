// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.it;

import adversarial.v1.BigNumHolderPxfMeta;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.DecodeLimits;
import org.protowire.pxf.Format;
import org.protowire.pxf.Parser;
import org.protowire.pxf.PxfException;
import org.protowire.pxf.android.LiteWireReader;
import org.protowire.pxf.android.LiteWireWriter;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The lite tier's half of #201, on a generated companion: a BigFloat
 * precision past an int is a positioned PxfException with protowire-go's
 * text, and a zero whose precision is past the digit bound is not read into
 * text. The full tier's half is pxf's {@code BigFloatPrecBoundTest}.
 */
final class BigFloatPrecBoundLiteTest {

    /** Wide enough for a p4294967295 suffix: its literal has 1292913988 digits. */
    private static final DecodeLimits RAISED = DecodeLimits.defaults().withMaxNumericLiteralDigits(1_300_000_000);

    private static PxfException refused(String doc) {
        return assertThrows(PxfException.class, () -> LiteWireWriter.encode(
                Parser.parse(doc.getBytes(StandardCharsets.UTF_8), RAISED), BigNumHolderPxfMeta.INSTANCE, RAISED));
    }

    @Test
    void aSuffixPastAnIntIsAPositionedPxfException() {
        assertEquals("1:13: big float literal 1: precision p2147483648 exceeds the widest mantissa big.Float can hold,"
                + " 2147483647 bits", refused("big_float = 1p2147483648").getMessage());
    }

    @Test
    void aValueBelowTheWiresExponentRangeIsAPositionedPxfException() {
        assertEquals("1:13: big float literal 1e-300 is below the wire's exponent range",
                refused("big_float = 1e-300p2147483647").getMessage());
    }

    /** {@code big_float = {prec: 13604}}: a zero whose literal, 0p13604, every reader refuses. */
    @Test
    void aZeroPastTheDigitBoundIsNotReadIntoText() {
        PxfException e = assertThrows(PxfException.class,
                () -> LiteWireReader.toAst(HexFormat.of().parseHex("1a0318a46a"), BigNumHolderPxfMeta.INSTANCE));
        assertEquals(e.position() + ": pxf.BigFloat prec 13604 bits renders to 4097 digits; MaxNumericLiteralDigits=4096",
                e.getMessage());
        // At the bound it is.
        assertEquals("big_float = 0p13603\n", Format.marshalDocument(
                LiteWireReader.toAst(HexFormat.of().parseHex("1a0318a36a"), BigNumHolderPxfMeta.INSTANCE)));
    }
}
