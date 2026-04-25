package com.trendvidia.protowire.pxf;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.trendvidia.protowire.pxf.testproto.AllTypes;
import com.trendvidia.protowire.pxf.testproto.Nested;
import com.trendvidia.protowire.pxf.testproto.Status;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Duration;
import com.google.protobuf.StringValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.BoolValue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PxfTest {

    @Test
    void scalarRoundTrip() {
        AllTypes msg = AllTypes.newBuilder()
                .setStringField("hello")
                .setInt32Field(42)
                .setInt64Field(1234567890L)
                .setBoolField(true)
                .setEnumField(Status.STATUS_ACTIVE)
                .setBytesField(ByteString.copyFromUtf8("blob"))
                .build();

        byte[] text = Pxf.marshal(msg);
        AllTypes.Builder b = AllTypes.newBuilder();
        Pxf.unmarshal(text, b);
        AllTypes decoded = b.build();

        assertEquals("hello", decoded.getStringField());
        assertEquals(42, decoded.getInt32Field());
        assertEquals(1234567890L, decoded.getInt64Field());
        assertTrue(decoded.getBoolField());
        assertEquals(Status.STATUS_ACTIVE, decoded.getEnumField());
        assertEquals("blob", decoded.getBytesField().toStringUtf8());
    }

    @Test
    void nestedAndRepeated() {
        AllTypes msg = AllTypes.newBuilder()
                .setNestedField(Nested.newBuilder().setName("inner").setValue(7).build())
                .addRepeatedString("a").addRepeatedString("b")
                .addRepeatedNested(Nested.newBuilder().setName("first").setValue(1).build())
                .addRepeatedNested(Nested.newBuilder().setName("second").setValue(2).build())
                .build();

        byte[] text = Pxf.marshal(msg);
        AllTypes.Builder b = AllTypes.newBuilder();
        Pxf.unmarshal(text, b);
        AllTypes decoded = b.build();

        assertEquals("inner", decoded.getNestedField().getName());
        assertEquals(2, decoded.getRepeatedStringCount());
        assertEquals("a", decoded.getRepeatedString(0));
        assertEquals(2, decoded.getRepeatedNestedCount());
        assertEquals("first", decoded.getRepeatedNested(0).getName());
    }

    @Test
    void mapsRoundTrip() {
        AllTypes msg = AllTypes.newBuilder()
                .putStringMap("env", "production")
                .putStringMap("team", "platform")
                .putIntMap(404, "Not Found")
                .putNestedMap("primary", Nested.newBuilder().setName("p").setValue(10).build())
                .build();
        byte[] text = Pxf.marshal(msg);
        AllTypes.Builder b = AllTypes.newBuilder();
        Pxf.unmarshal(text, b);
        AllTypes decoded = b.build();

        assertEquals("production", decoded.getStringMapMap().get("env"));
        assertEquals("Not Found", decoded.getIntMapMap().get(404));
        assertEquals("p", decoded.getNestedMapMap().get("primary").getName());
    }

    @Test
    void wellKnownTypes() {
        Instant now = Instant.parse("2024-01-15T10:30:00Z");
        AllTypes msg = AllTypes.newBuilder()
                .setTsField(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build())
                .setDurField(Duration.newBuilder().setSeconds(5400).build()) // 1h30m
                .setNullableString(StringValue.of("present"))
                .setNullableInt(Int32Value.of(42))
                .setNullableBool(BoolValue.of(true))
                .build();
        byte[] text = Pxf.marshal(msg);
        AllTypes.Builder b = AllTypes.newBuilder();
        Pxf.unmarshal(text, b);
        AllTypes decoded = b.build();
        assertEquals(now.getEpochSecond(), decoded.getTsField().getSeconds());
        assertEquals(5400L, decoded.getDurField().getSeconds());
        assertEquals("present", decoded.getNullableString().getValue());
        assertEquals(42, decoded.getNullableInt().getValue());
        assertTrue(decoded.getNullableBool().getValue());
    }

    @Test
    void exampleFixtureRoundTrip() throws IOException {
        byte[] fixture;
        try (InputStream in = getClass().getResourceAsStream("/example.pxf")) {
            assertNotNull(in, "fixture missing");
            fixture = in.readAllBytes();
        }
        Descriptor desc = AllTypes.getDescriptor();

        // PXF -> binary
        DynamicMessage parsed = Pxf.unmarshal(fixture, desc);

        // binary -> PXF -> binary again
        byte[] pxfOut = Pxf.marshal(parsed);
        DynamicMessage parsed2 = Pxf.unmarshal(pxfOut, desc);

        assertEquals(parsed, parsed2, "PXF↔binary↔PXF round trip mismatch");
    }

    @Test
    void unmarshalFullTracksPresence() {
        String src = """
                @type test.v1.AllTypes
                string_field = "hello"
                nullable_string = null
                """;
        AllTypes.Builder b = AllTypes.newBuilder();
        Result r = Pxf.unmarshalFull(src.getBytes(StandardCharsets.UTF_8), b);

        assertTrue(r.isSet("string_field"));
        assertTrue(r.isNull("nullable_string"));
        assertTrue(r.isAbsent("int32_field"));
    }

    @Test
    void astPreservesComments() {
        String src = """
                @type test.v1.AllTypes

                # leading comment
                string_field = "x"

                # block comment
                nested_field {
                  name = "n"
                }
                """;
        Ast.Document doc = Pxf.parse(src);
        assertEquals("test.v1.AllTypes", doc.typeUrl());
        assertEquals(2, doc.entries().size());
        // formatter round-trips
        String back = new String(Pxf.formatDocument(doc), StandardCharsets.UTF_8);
        assertTrue(back.contains("# leading comment"));
        assertTrue(back.contains("# block comment"));
    }

    @Test
    void oneofConflictRejected() {
        String src = """
                @type test.v1.AllTypes
                text_choice = "a"
                number_choice = 1
                """;
        AllTypes.Builder b = AllTypes.newBuilder();
        assertThrows(PxfException.class, () -> Pxf.unmarshal(src.getBytes(StandardCharsets.UTF_8), b));
    }

    @Test
    void nullForbiddenInRepeated() {
        String src = """
                @type test.v1.AllTypes
                repeated_string = ["a", null, "b"]
                """;
        AllTypes.Builder b = AllTypes.newBuilder();
        assertThrows(PxfException.class, () -> Pxf.unmarshal(src.getBytes(StandardCharsets.UTF_8), b));
    }

    @Test
    void nullForbiddenInMapValue() {
        String src = """
                @type test.v1.AllTypes
                string_map = { a: null }
                """;
        AllTypes.Builder b = AllTypes.newBuilder();
        assertThrows(PxfException.class, () -> Pxf.unmarshal(src.getBytes(StandardCharsets.UTF_8), b));
    }

    @Test
    void wrapperSugar() {
        String src = """
                @type test.v1.AllTypes
                nullable_int = 42
                nullable_string = "hi"
                """;
        AllTypes.Builder b = AllTypes.newBuilder();
        Pxf.unmarshal(src.getBytes(StandardCharsets.UTF_8), b);
        AllTypes m = b.build();
        assertEquals(42, m.getNullableInt().getValue());
        assertEquals("hi", m.getNullableString().getValue());
    }

    @Test
    void durationFormatRoundTrip() {
        String src = """
                @type test.v1.AllTypes
                dur_field = 1h30m45s
                """;
        AllTypes.Builder b = AllTypes.newBuilder();
        Pxf.unmarshal(src.getBytes(StandardCharsets.UTF_8), b);
        AllTypes m = b.build();
        long expected = 3600L + 1800L + 45L;
        assertEquals(expected, m.getDurField().getSeconds());
    }
}
