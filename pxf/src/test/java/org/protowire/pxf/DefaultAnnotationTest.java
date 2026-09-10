// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Message;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.MapDefault;
import org.protowire.pxf.testproto.RepeatedDefault;
import org.protowire.pxf.testproto.RepeatedIntDefault;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code (pxf.default)} / {@code (pxf.required)} post-decode pass
 * ({@code FastDecoder.postDecode}) against the schemas in
 * {@code annotated.proto}: draft -01 §annotation-extensions "Default
 * Placement" (#52) and "Oneof Members" (#53).
 */
class DefaultAnnotationTest {

    private static Result decodeFull(String doc, Message.Builder b) {
        return UnmarshalOptions.defaults().unmarshalFull(doc.getBytes(StandardCharsets.UTF_8), b);
    }

    private static PxfException assertRejected(String doc, Message.Builder b, String needle) {
        PxfException e = assertThrows(PxfException.class, () -> decodeFull(doc, b));
        assertTrue(e.getMessage().contains(needle), e.getMessage());
        return e;
    }

    // -- #52: placements one literal cannot denote --------------------------

    // The #52 repro: an absent repeated field with a default used to reach
    // Builder.setField with a String, which cast it to List and threw
    // ClassCastException out of the decoder.
    @Test
    void repeatedDefaultIsAPxfError() {
        assertRejected("", RepeatedDefault.newBuilder(),
                "default values not supported for repeated field \"tags\"");
    }

    // Every scalar arm has the same shape — not a string-only bug.
    @Test
    void repeatedIntDefaultIsAPxfError() {
        assertRejected("", RepeatedIntDefault.newBuilder(),
                "default values not supported for repeated field \"counts\"");
    }

    // A map field's message names the placement, not protobuf's synthetic
    // LabelsEntry type. isRepeated() is true for maps, so the map check
    // must run first.
    @Test
    void mapDefaultNamesTheMapField() {
        PxfException e = assertRejected("", MapDefault.newBuilder(),
                "default values not supported for map field \"labels\"");
        assertTrue(!e.getMessage().contains("Entry"), e.getMessage());
    }

    // The guard only fires on the absent-field path: a document that
    // populates the field never applies the default.
    @Test
    void populatedRepeatedFieldDecodesDespiteTheAnnotation() {
        RepeatedDefault.Builder b = RepeatedDefault.newBuilder();
        decodeFull("tags = [\"a\", \"b\"]", b);
        assertEquals(2, b.getTagsCount());
    }
}
