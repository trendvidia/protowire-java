// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import arr.v1.Arr.Key;
import arr.v1.Arr.Record;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixed-length arrays that are not text survive XML import and export (draft
 * -01 § Fixed-Length Arrays, #179). A {@code uint8} or {@code int8} array
 * imports as {@code bytes} of the same length, an array of a wider primitive is
 * refused, and a {@code bytes} field exports as a {@code uint8} array. The
 * schema and cases are protowire-go's {@code TestXMLToProtoOctetArray},
 * {@code TestXMLToProtoRefusesWideArray}, {@code TestXMLRoundTripFixedArrays}
 * and {@code TestXMLRoundTripBytesLengthOne}.
 */
final class FixedArrayTest {

    private static String resource(String path) throws IOException {
        try (InputStream in = FixedArrayTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String schema() throws IOException {
        return resource("/arrays/arr.xml");
    }

    private static String importXml(String xml) {
        return Convert.xmlToProto(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertContains(String text, String line) {
        assertTrue(text.contains(line), line + "\n--- in ---\n" + text);
    }

    @Test
    void anOctetArrayImportsAsBytes() throws IOException {
        String proto = importXml(schema());
        for (String line : new String[] {
            "bytes uuid = 2 [(sbe.length) = 16];",
            "bytes signed = 3 [(sbe.length) = 3];",
            "bytes raw = 1 [(sbe.length) = 6];",
            "uint32 tail = 5;",
        }) {
            assertContains(proto, line);
        }
    }

    /** The compiled copy is this output, so the build has compiled what the importer writes. */
    @Test
    void theCompiledProtoIsTheImportersOutput() throws IOException {
        String compiled = resource("/arrays/arr.proto");
        assertEquals(importXml(schema()), compiled.substring(compiled.indexOf("syntax = ")));
    }

    /** The block keeps its layout: before #179 the uuid took one byte and the tail moved. */
    @Test
    void theBlockKeepsItsLayout() {
        Codec codec = Codec.of(Record.getDescriptor().getFile());
        byte[] uuid = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, (byte) 0xFF};
        byte[] data = codec.marshal(Record.newBuilder()
                .setUuid(ByteString.copyFrom(uuid))
                .setTail(0xA1B2C3D4)
                .build());
        // Header(8) + name(4) + uuid(16) + signed(3) + key(6+4) + tail(4).
        assertEquals(8 + 4 + 16 + 3 + 10 + 4, data.length);
        assertArrayEquals(uuid, Arrays.copyOfRange(data, 12, 28));
        assertArrayEquals(new byte[] {(byte) 0xD4, (byte) 0xC3, (byte) 0xB2, (byte) 0xA1},
                Arrays.copyOfRange(data, data.length - 4, data.length));

        Record.Builder back = Record.newBuilder();
        codec.unmarshal(data, back);
        assertArrayEquals(uuid, back.getUuid().toByteArray());
        assertEquals(0xA1B2C3D4, back.getTail());
    }

    /** No proto field holds an array of a wider primitive, so the import refuses it and names it. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "<type name=\"Quad\" primitiveType=\"int32\" length=\"4\"/>|<field name=\"q\" id=\"1\" type=\"Quad\"/>",
        "<composite name=\"Box\"><type name=\"sides\" primitiveType=\"uint16\" length=\"2\"/></composite>|<field name=\"b\" id=\"1\" type=\"Box\"/>",
    })
    void aWideArrayIsRefused(String types, String field) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<sbe:messageSchema xmlns:sbe=\"http://fixprotocol.io/2016/sbe\" package=\"w\" id=\"1\" version=\"0\" byteOrder=\"littleEndian\">\n"
                + "    <types>" + types + "</types>\n"
                + "    <sbe:message name=\"M\" id=\"1\">" + field + "</sbe:message>\n"
                + "</sbe:messageSchema>";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> importXml(xml));
        assertContains(e.getMessage(), "which no proto field represents");
    }

    @Test
    void aRefusalNamesTheTypeFieldAndMessage() throws IOException {
        String xml = schema().replace("primitiveType=\"uint8\" length=\"16\"", "primitiveType=\"uint64\" length=\"2\"");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> importXml(xml));
        assertEquals("sbe: message \"Record\": field \"uuid\": type \"Uuid\" is an array of 2 uint64,"
                + " which no proto field represents", e.getMessage());
    }

    /**
     * XML → proto → XML → proto gives the same proto, apart from the comment
     * naming the Latin-1 encoding, which the export does not carry. The same
     * fields, types and annotations make the same descriptors, so the same
     * values encode to the same bytes under both. Before #179 the export wrote
     * a {@code bytes} field as a {@code char} array, which imports back as a
     * {@code string}.
     */
    @Test
    void xmlToProtoToXmlToProtoKeepsEveryField() throws IOException {
        String proto1 = importXml(schema());
        String xml2 = Convert.protoToXml(Record.getDescriptor().getFile());
        assertContains(xml2, "<type name=\"bytes16\" primitiveType=\"uint8\" length=\"16\"/>");
        assertContains(xml2, "<field name=\"name\" id=\"1\" type=\"bytes4\"/>");
        assertContains(xml2, "<type name=\"raw\" primitiveType=\"uint8\" length=\"6\"/>");

        String proto2 = importXml(xml2);
        assertEquals(withoutComments(proto1), withoutComments(proto2));
        assertTrue(proto1.contains("// SBE characterEncoding \"ISO-8859-1\""), proto1);
    }

    /** The compiled schema writes the bytes of the same values as the generated one reads. */
    @Test
    void theRoundTrippedSchemaCarriesEveryByte() {
        Codec codec = Codec.of(Record.getDescriptor().getFile());
        Record in = Record.newBuilder()
                .setName(ByteString.copyFrom(new byte[] {'c', 'a', 'f', (byte) 0xE9}))
                .setUuid(ByteString.copyFromUtf8("0123456789abcdef"))
                .setSigned(ByteString.copyFrom(new byte[] {(byte) 0x80, 0x7F, 0x01}))
                .setKey(Key.newBuilder().setRaw(ByteString.copyFromUtf8("abcdef")).setSeq(7))
                .setTail(9)
                .build();
        Record.Builder back = Record.newBuilder();
        codec.unmarshal(codec.marshal(in), back);
        assertEquals(in, back.build());
    }

    private static String withoutComments(String proto) {
        return proto.lines().filter(l -> !l.strip().startsWith("//")).collect(Collectors.joining("\n"));
    }

    /**
     * A {@code bytes} field of length 1 exports as a {@code uint8} of length 1,
     * which FIX SBE reads as a single {@code uint8}, so it imports back as
     * {@code uint32} with {@code (sbe.encoding) = "uint8"}: the same byte in the
     * block, not {@code bytes}. Pinned so a change to that reading is a
     * decision, not an accident.
     */
    @Test
    void aBytesFieldOfLengthOneComesBackAsOneByte() {
        String xml = Convert.protoToXml(one.v1.One.M.getDescriptor().getFile());
        assertContains(xml, "<type name=\"bytes1\" primitiveType=\"uint8\" length=\"1\"/>");
        assertContains(importXml(xml), "uint32 b = 1 [(sbe.encoding) = \"uint8\"];");
    }
}
