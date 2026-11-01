// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.protowire.pxf.testproto.KeyNests;
import org.protowire.pxf.testproto.KeyedNameHolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An entry name of a keyed block populates the element's key field (draft -01
 * §3.13), so it counts as the document supplying that field. The post-decode
 * pass reads presence only: without the mark, a default on the key replaces
 * the name, a required key is reported absent, and a default on a oneof
 * sibling of the key clears it. Mirrors protowire-go's
 * {@code keyed_entry_name_presence_test.go} (protowire-go#214).
 */
class KeyedEntryNameTest {

    private static final UnmarshalOptions OPTS =
            new UnmarshalOptions(url -> KeyNests.getDescriptor(), false, false);

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static KeyedNameHolder decode(String doc) {
        KeyedNameHolder.Builder b = KeyedNameHolder.newBuilder();
        OPTS.unmarshalFull(utf8(doc), b);
        return b.build();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "kd { alpha {} }                  | alpha     | kd[0].name",
            "kd { \"not ident\" {} }          | not ident | kd[0].name",
            "kd { alpha = {} }                | alpha     | kd[0].name",
            "kd = { alpha {} }                | alpha     | kd[0].name",
            "kd { a {} alpha {} }             | alpha     | kd[1].name",
            "kd { alpha { name = \"alpha\" } }| alpha     | kd[0].name",
            "kr { alpha {} }                  | alpha     | kr[0].name",
            "ko { alpha {} }                  | alpha     | ko[0].name",
            "kn { a { inner { alpha {} } } }  | alpha     | kn[0].inner[0].name",
    })
    void theEntryNameSuppliesTheKeyField(String doc, String want, String mark) {
        KeyedNameHolder.Builder b = KeyedNameHolder.newBuilder();
        Result r = OPTS.unmarshalFull(utf8(doc), b);
        KeyedNameHolder h = b.build();
        String got;
        if (doc.startsWith("kr")) got = h.getKr(0).getName();
        else if (doc.startsWith("ko")) got = h.getKo(0).getName();
        else if (doc.startsWith("kn")) got = h.getKn(0).getInner(0).getName();
        else got = h.getKd(h.getKdCount() - 1).getName();
        assertEquals(want, got, doc);
        assertTrue(r.isSet(mark), mark + " in " + r.presentFieldsView());
    }

    /** Only an entry name counts as supplying the key: the anonymous form leaves it absent. */
    @Test
    void anAnonymousElementTakesTheDefault() {
        assertEquals("dflt", decode("kd = [{}]").getKd(0).getName());
        PxfException e = assertThrows(PxfException.class, () -> decode("kr = [{}]"));
        assertTrue(e.getMessage().contains("required field \"kr[0].name\" is absent"), e.getMessage());
    }

    @Test
    void aOneofSiblingDefaultStaysOff() {
        KeyedNameHolder h = decode("ko { alpha {} }");
        assertEquals("alpha", h.getKo(0).getName());
        assertEquals("", h.getKo(0).getOther());
    }

    /** An Any body records its fields under the Any's path. */
    @Test
    void insideAnAnyBody() throws Exception {
        KeyedNameHolder h = decode("payload { @type = \"test.v1.KeyNests\"\n inner { alpha {} } }");
        KeyNests inner = KeyNests.parseFrom(h.getPayload().getValue());
        assertEquals("alpha", inner.getInner(0).getName());
    }

    /** A dataset cell takes the same decoder. */
    @Test
    void inADatasetCell() throws IOException {
        byte[] table = utf8("@dataset test.v1.KeyedNameHolder (kd)\n({ alpha {} })\n");
        DatasetReader tr = new DatasetReader(new ByteArrayInputStream(table), OPTS);
        KeyedNameHolder.Builder b = KeyedNameHolder.newBuilder();
        assertTrue(tr.scan(b));
        assertEquals("alpha", b.getKd(0).getName());
    }
}
