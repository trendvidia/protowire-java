// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import com.google.protobuf.ByteString;
import enc.v1.Enc.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The XML importer maps a {@code char} to {@code string} only when its
 * {@code characterEncoding} is UTF-8 compatible, and to {@code bytes}
 * otherwise (draft -01 § Character Encoding, protowire#494, #173). The
 * schema is protowire-go's TestXMLToProtoCharacterEncoding.
 */
final class CharacterEncodingTest {

    private static String resource(String path) throws IOException {
        try (InputStream in = CharacterEncodingTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String generated() throws IOException {
        return Convert.xmlToProto(resource("/charenc/enc.xml").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void eachEncodingMapsToItsType() throws IOException {
        String proto = generated();
        for (String line : new String[] {
            "string plain = 1 [(sbe.length) = 4];",
            "string ascii = 2 [(sbe.length) = 4];",
            "string us_ascii = 3 [(sbe.length) = 4];",
            "string utf8 = 4 [(sbe.length) = 4];",
            "  // SBE characterEncoding \"ISO-8859-1\" is not UTF-8 compatible, so this field is bytes.\n"
                + "  bytes latin1 = 5 [(sbe.length) = 4];",
            "  // SBE characterEncoding \"windows-1252\" is not UTF-8 compatible, so this field is bytes.\n"
                + "  bytes code = 1 [(sbe.length) = 2];",
            "string tag = 2 [(sbe.length) = 2];",
        }) {
            assertTrue(proto.contains(line), line + "\n--- in ---\n" + proto);
        }
    }

    /** The compiled copy is this output, so the build has compiled what the importer writes. */
    @Test
    void theCompiledProtoIsTheImportersOutput() throws IOException {
        String compiled = resource("/charenc/enc.proto");
        assertEquals(generated(), compiled.substring(compiled.indexOf("syntax = ")));
    }

    @Test
    void aLatin1FieldCarriesItsBytes() {
        Codec codec = Codec.of(Text.getDescriptor().getFile());
        byte[] latin1 = {'c', 'a', 'f', (byte) 0xE9};
        byte[] data = codec.marshal(Text.newBuilder().setLatin1(ByteString.copyFrom(latin1)).build());
        Text.Builder back = Text.newBuilder();
        codec.unmarshal(data, back);
        assertArrayEquals(latin1, back.getLatin1().toByteArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "US-ASCII", "us_ascii", "ASCII", "ANSI_X3.4-1968", "IBM367", "csASCII", "UTF-8", "utf8", " Utf-8 "})
    void utf8CompatibleNames(String name) {
        assertTrue(Convert.isUtf8Compatible(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ISO-8859-1", "windows-1252", "UTF-16", "latin1", "Shift_JIS"})
    void otherNames(String name) {
        assertFalse(Convert.isUtf8Compatible(name), name);
    }
}
