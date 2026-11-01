// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.it;

import org.junit.jupiter.api.Test;
import org.protowire.proto.pxf.Secret;
import org.protowire.pxf.PxfException;
import wkt.test.v1.Secrets;
import wkt.test.v1.SecretsPxfCodec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code pxf.Secret} through a generated companion, against a typed builder. */
final class SecretLiteTest {
    private static Secret value(String v) {
        return Secret.newBuilder().setValue(v).build();
    }

    @Test
    void literalIsAcceptedOnASingularField() {
        assertArrayEquals(Secrets.newBuilder().setPw(value("x")).build().toByteArray(),
            SecretsPxfCodec.unmarshal("pw = \"x\"").toByteArray());
    }

    @Test
    void eitherFormIsAcceptedPerListElement() {
        Secrets want = Secrets.newBuilder()
            .addList(value("x"))
            .addList(Secret.newBuilder().setValue("y").setHint("h"))
            .build();
        assertArrayEquals(want.toByteArray(),
            SecretsPxfCodec.unmarshal("list = [ \"x\", { value = \"y\" hint = \"h\" } ]").toByteArray());
    }

    @Test
    void eitherFormIsAcceptedPerMapValue() {
        Secrets got = SecretsPxfCodec.unmarshal("by_name = { a: \"x\" b: { value = \"y\" fingerprint = \"f\" } }");
        assertEquals(value("x"), got.getByNameOrThrow("a"));
        assertEquals(Secret.newBuilder().setValue("y").setFingerprint("f").build(), got.getByNameOrThrow("b"));
    }

    @Test
    void blockFormStillDecodes() {
        assertArrayEquals(Secrets.newBuilder().setPw(value("x")).build().toByteArray(),
            SecretsPxfCodec.unmarshal("pw { value = \"x\" }").toByteArray());
    }

    @Test
    void emptyLiteralSetsTheField() {
        assertTrue(SecretsPxfCodec.unmarshal("pw = \"\"").hasPw());
    }

    @Test
    void anythingElseIsRejected() {
        PxfException e = assertThrows(PxfException.class, () -> SecretsPxfCodec.unmarshal("pw = 1"));
        assertTrue(e.getMessage().contains("a string literal or '{'"), e.getMessage());
    }

    @Test
    void valueOnlyIsWrittenAsTheLiteral() {
        String text = SecretsPxfCodec.marshal(Secrets.newBuilder()
            .setPw(value("x"))
            .addList(value("y"))
            .putByName("a", value("z"))
            .build());
        assertTrue(text.contains("pw = \"x\""), text);
        assertFalse(text.contains("value ="), text);
    }

    @Test
    void metadataKeepsTheBlock() {
        Secrets want = Secrets.newBuilder()
            .setPw(Secret.newBuilder().setValue("x").setHint("h"))
            .addList(Secret.newBuilder().setFingerprint("f"))
            .putByName("a", Secret.newBuilder().setValue("z").setHint("m").build())
            .build();
        String text = SecretsPxfCodec.marshal(want);
        assertTrue(text.contains("hint = \"h\""), text);
        assertArrayEquals(want.toByteArray(), SecretsPxfCodec.unmarshal(text).toByteArray(), text);
    }

    @Test
    void valueOnlyRoundTrips() {
        Secrets want = Secrets.newBuilder().setPw(value("x")).addList(value("y")).putByName("a", value("z")).build();
        assertArrayEquals(want.toByteArray(), SecretsPxfCodec.unmarshal(SecretsPxfCodec.marshal(want)).toByteArray());
    }
}
