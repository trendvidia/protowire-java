// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Message;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.MapDefault;
import org.protowire.pxf.testproto.NestedOneDefault;
import org.protowire.pxf.testproto.NullableSibling;
import org.protowire.pxf.testproto.OneDefault;
import org.protowire.pxf.testproto.RepeatedDefault;
import org.protowire.pxf.testproto.RepeatedIntDefault;
import org.protowire.pxf.testproto.RequiredMember;
import org.protowire.pxf.testproto.SyntheticOneof;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // -- #53: (pxf.default) / (pxf.required) on a oneof member --------------

    // The #53 repro: a document that chooses one arm keeps it. Before the
    // fix this decoded to b="bbb", case b — the written value cleared, not
    // shadowed.
    @Test
    void oneofDefaultDoesNotClobberChosenArm() {
        OneDefault.Builder b = OneDefault.newBuilder();
        decodeFull("a = \"written\"", b);
        assertEquals(OneDefault.ChoiceCase.A, b.getChoiceCase());
        assertEquals("written", b.getA());
        assertEquals("", b.getB(), "sibling default must not be applied");
    }

    // Choosing the annotated arm itself is likewise untouched.
    @Test
    void oneofDefaultSuppressedWhenItsOwnArmChosen() {
        OneDefault.Builder b = OneDefault.newBuilder();
        decodeFull("b = \"written\"", b);
        assertEquals(OneDefault.ChoiceCase.B, b.getChoiceCase());
        assertEquals("written", b.getB());
    }

    // With no member present the default still applies — the capability
    // the rule deliberately preserves rather than forbidding outright.
    @Test
    void oneofDefaultAppliesWhenWholeOneofAbsent() {
        OneDefault.Builder b = OneDefault.newBuilder();
        Result r = decodeFull("", b);
        assertEquals(OneDefault.ChoiceCase.B, b.getChoiceCase());
        assertEquals("bbb", b.getB());
        assertFalse(r.isSet("b"), "a default is not an input-set field");
    }

    // A field outside the oneof is unaffected by any of this.
    @Test
    void oneofRuleDoesNotAffectOrdinaryFields() {
        OneDefault.Builder b = OneDefault.newBuilder();
        decodeFull("a = \"written\"", b);
        assertEquals("out", b.getOutside());
    }

    // A member bound to null counts as present, so it suppresses a
    // sibling's default — matching the rule that null suppresses a field's
    // own default because null is intentional.
    @Test
    void nullOneofMemberSuppressesSiblingDefault() {
        NullableSibling.Builder b = NullableSibling.newBuilder();
        Result r = decodeFull("w = null", b);
        assertTrue(r.isNull("w"));
        assertEquals("", b.getB(), "a null sibling is present, so the default must not fire");
        assertEquals(NullableSibling.ChoiceCase.CHOICE_NOT_SET, b.getChoiceCase());
    }

    // proto3 `optional` puts the field in a synthetic single-member oneof.
    // Nothing can clear it, so it keeps plain per-field presence and its
    // default must still apply — the regression guard for the isSynthetic
    // test in siblingPresent.
    @Test
    void syntheticOneofStillTakesItsDefault() {
        SyntheticOneof.Builder b = SyntheticOneof.newBuilder();
        decodeFull("", b);
        assertTrue(b.hasOpt());
        assertEquals("syn", b.getOpt());
    }

    // The pre-fix symptom for (pxf.required): a document choosing a valid
    // arm was rejected for the absence of a different one.
    @Test
    void requiredOneofMemberAcceptsASiblingArm() {
        RequiredMember.Builder b = RequiredMember.newBuilder();
        decodeFull("b = \"written\"", b);
        assertEquals(RequiredMember.ChoiceCase.B, b.getChoiceCase());
        assertEquals("written", b.getB());
    }

    // ... while a document that sets no arm at all is still rejected: the
    // only coherent runtime reading is "the oneof must be set".
    @Test
    void requiredOneofMemberRejectsAnEmptyOneof() {
        assertRejected("", RequiredMember.newBuilder(), "required field \"a\" is absent");
    }

    // Presence is keyed by path, so the rule holds at every nesting level:
    // the inner oneof's chosen arm survives, and the inner default outside
    // the oneof still applies.
    @Test
    void oneofRuleAppliesInsideNestedMessages() {
        NestedOneDefault.Builder b = NestedOneDefault.newBuilder();
        decodeFull("inner { a = \"written\" }", b);
        OneDefault inner = b.getInner();
        assertEquals(OneDefault.ChoiceCase.A, inner.getChoiceCase());
        assertEquals("written", inner.getA());
        assertEquals("", inner.getB());
        assertEquals("out", inner.getOutside());
    }
}
