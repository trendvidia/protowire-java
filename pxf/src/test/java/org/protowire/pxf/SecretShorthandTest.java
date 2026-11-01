// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import dataset.v1.Paths.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.protowire.proto.pxf.Secret;
import test.v1.secrets.Secrets.Vault;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code pxf.Secret} is written as a string literal or a block (draft -01
 * "Secret", {@code proto/pxf/secret.proto}): a list element and a map value
 * may take either form, and the encoder uses the literal unless the block is
 * needed to keep {@code hint} or {@code fingerprint}.
 */
class SecretShorthandTest {

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static Vault decode(String doc) throws InvalidProtocolBufferException {
        return Vault.parseFrom(Pxf.unmarshal(utf8(doc), Vault.getDescriptor()).toByteArray());
    }

    private static Result full(String doc) {
        return Pxf.unmarshalFull(utf8(doc), DynamicMessage.newBuilder(Vault.getDescriptor()));
    }

    private static String marshal(Vault v) { return new String(Pxf.marshal(v), StandardCharsets.UTF_8); }

    private static Secret value(String v) { return Secret.newBuilder().setValue(v).build(); }

    private static final Secret WITH_HINT = Secret.newBuilder().setValue("y").setHint("h").build();
    private static final Secret WITH_FINGERPRINT = Secret.newBuilder().setValue("z").setFingerprint("f").build();

    // -- decode --------------------------------------------------------------

    @Test
    void aSingularFieldTakesEitherForm() throws Exception {
        assertEquals(value("x"), decode("pw = \"x\"").getPw());
        assertEquals(value("x"), decode("pw { value = \"x\" }").getPw());
        assertEquals(value("x"), decode("pw = { value = \"x\" }").getPw());
        assertEquals(value("x"), decode("nested { pw = \"x\" }").getNested().getPw());
    }

    @Test
    void aListTakesEitherFormPerElement() throws Exception {
        assertEquals(List.of(value("x"), WITH_HINT), decode("list = [ \"x\", { value = \"y\" hint = \"h\" } ]").getListList());
    }

    @Test
    void aMapTakesEitherFormPerValue() throws Exception {
        Vault v = decode("by_name = { a: \"x\" b: { value = \"y\" hint = \"h\" } }");
        assertEquals(value("x"), v.getByNameOrThrow("a"));
        assertEquals(WITH_HINT, v.getByNameOrThrow("b"));
    }

    @Test
    void theEmptyStringSetsTheField() throws Exception {
        assertTrue(decode("pw = \"\"").hasPw());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pw = 1", "pw = true", "pw = b\"eA==\"", "list = [ 1 ]", "by_name = { a: 1 }"})
    void onlyAStringIsTheLiteral(String doc) {
        PxfException e = assertThrows(PxfException.class, () -> decode(doc));
        assertTrue(e.getMessage().contains("string literal"), e.getMessage());
    }

    @Test
    void anInvalidStringReportsTheLexersError() {
        byte[] doc = {'p', 'w', ' ', '=', ' ', '"', (byte) 0xff, '"'};
        PxfException e = assertThrows(PxfException.class, () -> Pxf.unmarshal(doc, Vault.getDescriptor()));
        assertTrue(e.getMessage().contains("UTF-8"), e.getMessage());
    }

    @Test
    void aSecondBindingIsStillRefused() {
        assertThrows(PxfException.class, () -> decode("pw = \"x\"\npw = \"y\""));
        assertThrows(PxfException.class, () -> decode("pw = \"x\"\npw { hint = \"h\" }"));
    }

    @Test
    void theLiteralReportsTheSamePresenceAsTheBlock() {
        for (String path : List.of("pw", "pw.value")) {
            assertTrue(full("pw { value = \"x\" }").isSet(path), "block " + path);
            assertTrue(full("pw = \"x\"").isSet(path), "literal " + path);
        }
        assertTrue(full("list = [ \"x\" ]").isSet("list[0].value"));
        assertTrue(full("list = [ { value = \"x\" } ]").isSet("list[0].value"));
        assertTrue(full("by_name = { a: \"x\" }").isSet("by_name[\"a\"].value"));
        assertTrue(full("by_name = { a: { value = \"x\" } }").isSet("by_name[\"a\"].value"));
        assertTrue(full("nested { pw = \"x\" }").isSet("nested.pw.value"));
        assertFalse(full("pw = \"x\"").isSet("pw.hint"));
    }

    // -- encode --------------------------------------------------------------

    @Test
    void aValueAloneEncodesAsTheLiteral() {
        assertEquals("pw = \"x\"\n", marshal(Vault.newBuilder().setPw(value("x")).build()));
        assertEquals("pw = \"\"\n", marshal(Vault.newBuilder().setPw(Secret.getDefaultInstance()).build()));
    }

    @Test
    void metadataKeepsTheBlock() {
        assertEquals("pw {\n  value = \"y\"\n  hint = \"h\"\n}\n", marshal(Vault.newBuilder().setPw(WITH_HINT).build()));
        assertEquals("pw {\n  value = \"z\"\n  fingerprint = \"f\"\n}\n", marshal(Vault.newBuilder().setPw(WITH_FINGERPRINT).build()));
    }

    @Test
    void aListChoosesPerElement() {
        assertEquals("list = [\n  \"x\",\n  {\n    value = \"y\"\n    hint = \"h\"\n  }\n]\n",
                marshal(Vault.newBuilder().addList(value("x")).addList(WITH_HINT).build()));
    }

    @Test
    void aMapChoosesPerValue() {
        assertEquals("by_name = {\n  a: \"x\"\n  b: {\n    value = \"y\"\n    hint = \"h\"\n  }\n}\n",
                marshal(Vault.newBuilder().putByName("a", value("x")).putByName("b", WITH_HINT).build()));
    }

    @Test
    void everyFormRoundTrips() throws Exception {
        Vault v = Vault.newBuilder()
                .setPw(value("sp\"cial\n"))
                .addList(value("x")).addList(WITH_FINGERPRINT).addList(Secret.getDefaultInstance())
                .putByName("a", value("x")).putByName("b", WITH_HINT)
                .build();
        assertEquals(v, decode(marshal(v)));
    }

    // -- datasets ------------------------------------------------------------

    @Test
    void aDatasetCellTakesTheLiteral() throws Exception {
        byte[] doc = utf8("@dataset dataset.v1.Event (id, pw)\n(\"a\", \"s3cret\")\n");
        Event.Builder b = Event.newBuilder();
        assertTrue(new DatasetReader(new java.io.ByteArrayInputStream(doc)).scan(b));
        assertEquals("s3cret", b.getPw().getValue());
    }
}
