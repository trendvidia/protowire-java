// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.protowire.pxf.testproto.MapWellKnown;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A map value of a well-known type takes the literal a field or list element takes (#126). */
class MapWellKnownTest {

    private static MapWellKnown decode(String doc) {
        MapWellKnown.Builder b = MapWellKnown.newBuilder();
        Pxf.unmarshal(doc.getBytes(StandardCharsets.UTF_8), b);
        return b.build();
    }

    private static String encode(MapWellKnown m) {
        return new String(Pxf.marshal(m), StandardCharsets.UTF_8);
    }

    /** Each document is what the encoder writes, so text, message and text agree. */
    @ParameterizedTest
    @ValueSource(strings = {
        "ts = {\n  a: 2026-01-01T00:00:00Z\n}\n",
        "dur = {\n  a: 1h30m0s\n}\n",
        "str = {\n  a: \"x\"\n}\n",
        "byt = {\n  a: b\"eA==\"\n}\n",
        "flag = {\n  a: true\n}\n",
        "i32 = {\n  a: -1\n}\n",
        "i64 = {\n  a: 5000000000\n}\n",
        "u32 = {\n  a: 4000000000\n}\n",
        "u64 = {\n  a: 18446744073709551615\n}\n",
        "f32 = {\n  a: 1.5\n}\n",
        "f64 = {\n  a: 2.5\n}\n",
        "big = {\n  a: 123456789012345678901234567890\n}\n",
        "dec = {\n  a: 1.00\n}\n",
        "flt = {\n  a: 1.5p64\n}\n",
    })
    void eachTypeRoundTripsAsALiteral(String doc) {
        assertEquals(doc, encode(decode(doc)));
    }

    @Test
    void theBlockFormStillDecodesToTheSameMessage() {
        assertEquals(decode("ts = { a: 1970-01-01T00:00:10Z }"), decode("ts = { a: { seconds = 10 } }"));
        assertEquals(decode("i32 = { a: 7 }"), decode("i32 = { a: { value = 7 } }"));
    }

    @Test
    void aSetWrapperOfZeroIsKept() {
        MapWellKnown m = decode("i32 = { a: 0 }");
        assertEquals(1, m.getI32Count());
        assertEquals("i32 = {\n  a: 0\n}\n", encode(m));
    }

    @Test
    void theLiteralSetsTheValueNotTheEntryAlone() {
        Timestamp t = decode("ts = { a: 1970-01-01T00:00:10Z }").getTsOrThrow("a");
        assertEquals(10, t.getSeconds());
    }

    @Test
    void aPrecisionSuffixIsRefusedAndNamesTheMap() {
        PxfException e = assertThrows(PxfException.class, () -> decode("big = { a: 1p64 }"));
        assertTrue(e.getMessage().contains("big"), e.getMessage());
    }

    @Test
    void aLiteralOfTheWrongKindIsRefused() {
        assertThrows(PxfException.class, () -> decode("ts = { a: 5 }"));
        assertThrows(PxfException.class, () -> decode("dur = { a: \"x\" }"));
    }

    @Test
    void nullIsStillRefused() {
        assertThrows(PxfException.class, () -> decode("i32 = { a: null }"));
    }
}
