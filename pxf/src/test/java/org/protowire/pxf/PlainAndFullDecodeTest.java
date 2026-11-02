// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import org.junit.jupiter.api.Test;
import org.protowire.pxf.testproto.ElemDeep;
import org.protowire.pxf.testproto.ElemHolder;
import org.protowire.pxf.testproto.ElemItem;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which entry points apply {@code (pxf.default)} and {@code (pxf.required)},
 * as protowire-go has it (#112): {@code unmarshal} never does,
 * {@code unmarshalFull} does, and {@code skipPostDecode} turns that off while
 * keeping presence. A change to the plain path must fail here. Mirrors
 * {@code TestElementDefaults_NotAppliedWithoutPostDecode} and
 * {@code TestSkipPostDecode}.
 */
class PlainAndFullDecodeTest {

    private static final TypeResolver RESOLVER = url -> ElemItem.getDescriptor();
    private static final UnmarshalOptions OPTS = new UnmarshalOptions(RESOLVER, false, false);

    /** A required field missing from a list element, unset defaults in the element, the root and an Any body. */
    private static final byte[] DOC = "items = [{ deep { } }]\npayload { @type = \"x\" }".getBytes(StandardCharsets.UTF_8);

    @Test
    void fullRejectsWhatThePlainPathsAccept() {
        PxfException e = assertThrows(PxfException.class,
                () -> OPTS.unmarshalFull(DOC, ElemHolder.newBuilder()));
        assertTrue(e.getMessage().contains("required field \"items[0].deep.n\" is absent"), e.getMessage());
    }

    @Test
    void plainUnmarshalAppliesNothing() throws Exception {
        ElemHolder.Builder b = ElemHolder.newBuilder();
        OPTS.unmarshal(DOC, b);
        assertAppliedNothing(b);
    }

    @Test
    void skipPostDecodeAppliesNothing() throws Exception {
        ElemHolder.Builder b = ElemHolder.newBuilder();
        OPTS.withSkipPostDecode(true).unmarshalFull(DOC, b);
        assertAppliedNothing(b);
    }

    private static void assertAppliedNothing(ElemHolder.Builder b) throws Exception {
        assertEquals("", b.getRootLabel());
        assertEquals("", b.getItems(0).getLabel());
        assertEquals("", ElemItem.parseFrom(b.getPayload().getValue()).getLabel());
    }

    @Test
    void skipPostDecodeKeepsPresence() {
        Result r = OPTS.withSkipPostDecode(true).unmarshalFull(
                "items = [{ id = \"a\" }]\none = { id = \"b\" }".getBytes(StandardCharsets.UTF_8),
                ElemHolder.newBuilder());
        assertTrue(r.presentFieldsView().contains("items[0].id"));
        assertTrue(r.presentFieldsView().contains("one.id"));
    }

    @Test
    void skipPostDecodeSurvivesTheOtherWithers() {
        UnmarshalOptions o = UnmarshalOptions.defaults().withSkipPostDecode(true);
        assertTrue(o.withTypeResolver(RESOLVER).withDiscardUnknown(true).withSkipValidate(true)
                .withLimits(DecodeLimits.defaults()).skipPostDecode());
        assertFalse(UnmarshalOptions.defaults().skipPostDecode());
    }

    /** The pass a skipPostDecode caller runs: the same result as the full decode. */
    @Test
    void theHelpersReproduceThePass() {
        Descriptor item = ElemItem.getDescriptor();
        FieldDescriptor label = item.findFieldByName("label");
        assertEquals("item-dflt", Pxf.defaultLiteral(label));
        assertNull(Pxf.defaultLiteral(item.findFieldByName("id")));
        assertTrue(Pxf.isRequired(ElemDeep.getDescriptor().findFieldByName("n")));
        assertFalse(Pxf.isRequired(label));

        ElemItem.Builder b = ElemItem.newBuilder();
        Pxf.applyDefault(b, label, Pxf.defaultLiteral(label));
        assertEquals("item-dflt", b.getLabel());
    }

    @Test
    void applyDefaultRefusesARepeatedField() {
        FieldDescriptor items = ElemHolder.getDescriptor().findFieldByName("items");
        assertThrows(PxfException.class, () -> Pxf.applyDefault(ElemHolder.newBuilder(), items, "x"));
    }
}
